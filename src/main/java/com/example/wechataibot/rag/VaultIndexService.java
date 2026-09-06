package com.example.wechataibot.rag;

import com.example.wechataibot.config.ObsidianProperties;
import com.example.wechataibot.persistence.RagExcludePatternService;
import com.example.wechataibot.persistence.RagProtectService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Obsidian 库索引：启动时扫描 Markdown 文件，按标题/段落切成笔记块，保存在内存中。
 *
 * <p>路径判定统一走 {@link #classify(String, RuleSet)}，索引重建与 /api/vault/tree
 * 共用同一套规则，保证两边看到的状态一致。三层规则（并集，按序判定）：
 * <ol>
 *     <li><b>安全基线</b>（yml exclude-patterns，如 .mimocode/.obsidian）：不索引，
 *         且任何接口都不返回这些路径——不可通过 API 解锁的底线；</li>
 *     <li><b>排除规则字典</b>（rag_exclude_pattern 表 + 基线之外的语义相同）：不索引，
 *         文件树中显示为"已排除"并带来源规则；</li>
 *     <li><b>受保护目录</b>（yml protected-dirs，如 40-Life）：默认不索引，文件树中
 *         只显示折叠节点不露子文件名；持管理令牌可加入索引（rag_unlock_path 持久化，可逆）。</li>
 * </ol>
 */
@Service
public class VaultIndexService {

    private static final Logger log = LoggerFactory.getLogger(VaultIndexService.class);

    private final ObsidianProperties props;
    private final RagExcludePatternService patternService;
    private final RagProtectService protectService;
    private volatile List<NoteChunk> chunks = List.of();
    private volatile int fileCount = 0;
    private volatile String lastError = "";

    public VaultIndexService(ObsidianProperties props,
                             RagExcludePatternService patternService,
                             RagProtectService protectService) {
        this.props = props;
        this.patternService = patternService;
        this.protectService = protectService;
    }

    @PostConstruct
    public void init() {
        rebuild();
    }

    /** 全量重建索引，返回笔记块数量 */
    public synchronized int rebuild() {
        chunks = List.of();
        fileCount = 0;
        lastError = "";

        if (!props.isEnabled()) {
            log.info("Obsidian RAG 未启用（obsidian.vault-path 为空）");
            return 0;
        }

        Path root = Path.of(props.getVaultPath());
        if (!Files.isDirectory(root)) {
            lastError = "vault 路径不存在: " + root;
            log.warn(lastError);
            return 0;
        }

        List<NoteChunk> list = new ArrayList<>();
        RuleSet rules = currentRules();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".md"))
                    .forEach(p -> {
                        // 动态规则读取失败等场景下降级为仅基线，不能让索引整体失败
                        String rel = normalizeRel(p, root);
                        if (classify(rel, rules).status() == Status.INDEXED) {
                            list.addAll(chunkFile(p, root));
                        }
                    });
        } catch (IOException e) {
            lastError = "扫描 vault 失败: " + e.getMessage();
            log.error("扫描 Obsidian 库失败", e);
            return 0;
        }

        chunks = list;
        log.info("Obsidian 索引完成：{} 个文件，{} 个笔记块（{}）", fileCount, chunks.size(), root);
        return chunks.size();
    }

    public List<NoteChunk> getChunks() {
        return chunks;
    }

    public int getFileCount() {
        return fileCount;
    }

    public String getLastError() {
        return lastError;
    }

    // ==================== 路径判定（索引与文件树共用） ====================

    /** 一套完整的判定规则快照（加载一次、判定一批文件，避免每个文件查一次库） */
    public record RuleSet(List<String> baseline, List<String> dynamic,
                          List<String> protectedDirs, List<String> joinedProtected) {
    }

    /** 路径判定结果（dir = PROTECTED 时命中的保护目录，供文件树归并统计） */
    public record Classification(Status status, String rule, String dir) {
    }

    /** 路径状态：可检索 / 已排除 / 受保护未加入 / 安全基线（不可见） */
    public enum Status {
        INDEXED, EXCLUDED, PROTECTED, BASELINE
    }

    /** 加载当前规则快照；DB 异常时降级为仅基线（不让索引/文件树整体失败） */
    public RuleSet currentRules() {
        List<String> dynamic;
        List<String> joined;
        try {
            dynamic = patternService.activePatterns();
        } catch (Exception e) {
            log.warn("读取排除规则字典失败，本次仅使用 yml 基线：{}", e.getMessage());
            dynamic = List.of();
        }
        try {
            joined = protectService.listJoined();
        } catch (Exception e) {
            log.warn("读取保护目录状态失败，本次按全部未加入处理：{}", e.getMessage());
            joined = List.of();
        }
        return new RuleSet(props.getExcludePatterns(), dynamic, props.getProtectedDirs(), joined);
    }

    /**
     * 判定一个相对路径（必须已经 {@link #normalizeRel} 归一化：小写、正斜杠）。
     * 按基线 → 字典 → 保护目录的顺序返回首个命中。
     */
    public static Classification classify(String relPathLower, RuleSet rules) {
        String baselineRule = firstMatch(relPathLower, rules.baseline());
        if (baselineRule != null) {
            return new Classification(Status.BASELINE, baselineRule, null);
        }
        // 保护目录优先于字典/导航规则：未加入索引时子文件路径一律不可见（隐私优先），
        // 已加入索引则继续走字典/导航判定
        for (String dir : rules.protectedDirs()) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            String dirLower = dir.trim().toLowerCase();
            if (dirLower.endsWith("/")) {
                dirLower = dirLower.substring(0, dirLower.length() - 1);
            }
            // 子串包含语义（与基线/字典规则一致）：保护目录可能在库的任意层级，
            // 如配置 "40-Life" 必须罩住 "MyVault/40-Life/**"
            if (relPathLower.contains(dirLower + "/") || relPathLower.equals(dirLower)) {
                boolean joined = rules.joinedProtected() != null
                        && rules.joinedProtected().stream().anyMatch(j -> j.trim().equalsIgnoreCase(dir.trim()));
                if (!joined) {
                    return new Classification(Status.PROTECTED, null, dirLower);
                }
                break;
            }
        }
        String dynamicRule = firstMatch(relPathLower, rules.dynamic());
        if (dynamicRule != null) {
            return new Classification(Status.EXCLUDED, dynamicRule, null);
        }
        // 导航/索引文件检索价值低，不索引（文件树里标注为已排除）
        String name = relPathLower.substring(relPathLower.lastIndexOf('/') + 1);
        if (name.equals("_index.md") || name.endsWith("moc.md")) {
            return new Classification(Status.EXCLUDED, "导航文件(_index/moc)", null);
        }
        return new Classification(Status.INDEXED, null, null);
    }

    /** 相对路径归一化：小写、正斜杠（判定输入的统一格式） */
    public static String normalizeRel(Path file, Path root) {
        return root.relativize(file).toString().replace('\\', '/').toLowerCase();
    }

    /** 首个命中的规则（子串包含，忽略大小写——由调用方保证入参已小写） */
    private static String firstMatch(String relPathLower, List<String> patterns) {
        if (patterns == null) {
            return null;
        }
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank() && relPathLower.contains(pattern.toLowerCase())) {
                return pattern;
            }
        }
        return null;
    }

    // ==================== 文件读取与切块 ====================

    private List<NoteChunk> chunkFile(Path file, Path root) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            log.warn("读取失败，跳过：{}", file);
            return List.of();
        }

        String rel = normalizeRel(file, root);
        text = stripFrontmatter(text);

        List<NoteChunk> result = new ArrayList<>();
        String heading = "";
        StringBuilder current = new StringBuilder();
        int max = Math.max(100, props.getMaxChunkChars());

        for (String rawLine : text.split("\\R")) {
            String line = cleanLine(rawLine);
            if (line.isBlank()) {
                continue;
            }
            if (line.matches("^#{1,6}\\s.*")) {
                // 先收尾上一个块
                if (!current.isEmpty()) {
                    result.add(new NoteChunk(rel, heading, current.toString().trim()));
                    current.setLength(0);
                }
                heading = line.replaceAll("^#{1,6}\\s*", "").trim();
                continue;
            }
            if (current.length() + line.length() > max && !current.isEmpty()) {
                result.add(new NoteChunk(rel, heading, current.toString().trim()));
                current.setLength(0);
            }
            current.append(line).append('\n');
        }
        if (!current.isEmpty()) {
            result.add(new NoteChunk(rel, heading, current.toString().trim()));
        }
        fileCount++;
        return result;
    }

    /** 去掉 YAML frontmatter（--- 开头到下一个 ---） */
    private String stripFrontmatter(String text) {
        String t = text.stripLeading();
        if (t.startsWith("---")) {
            int end = t.indexOf("\n---", 3);
            if (end > 0) {
                return t.substring(end + 4);
            }
        }
        return t;
    }

    /** 清理行内 Markdown 符号，便于关键词匹配 */
    private String cleanLine(String line) {
        String s = line;
        s = s.replaceAll("!\\[\\[.*?\\]\\]", " ");   // 嵌入附件
        s = s.replaceAll("\\[\\[(.*?)\\]\\]", "$1"); // 双链 [[xxx]] -> xxx
        s = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1"); // 链接 [x](url) -> x
        s = s.replaceAll("[*_`>#|~]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }
}
