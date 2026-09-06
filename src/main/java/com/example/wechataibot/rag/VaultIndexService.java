package com.example.wechataibot.rag;

import com.example.wechataibot.config.ObsidianProperties;
import com.example.wechataibot.persistence.RagExcludePatternService;
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
 * <p>排除规则两层合并（并集）：{@link ObsidianProperties#getExcludePatterns()} 静态基线
 * + 数据字典动态规则（{@link RagExcludePatternService}，前端经 /api/rag/patterns 增删改，
 * 改后调 rebuild() 立即生效）。基线不可通过 API 移除，保护真正隐私的目录。
 */
@Service
public class VaultIndexService {

    private static final Logger log = LoggerFactory.getLogger(VaultIndexService.class);

    private final ObsidianProperties props;
    private final RagExcludePatternService patternService;
    private volatile List<NoteChunk> chunks = List.of();
    private volatile int fileCount = 0;
    private volatile String lastError = "";

    public VaultIndexService(ObsidianProperties props, RagExcludePatternService patternService) {
        this.props = props;
        this.patternService = patternService;
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
        // 动态规则只取一次（rebuild 同步执行，毫秒级）；DB 异常时降级为仅 yml 基线
        List<String> dynamicRules = loadDynamicRules();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".md"))
                    .filter(p -> notExcluded(p, dynamicRules))
                    .forEach(p -> list.addAll(chunkFile(p, root)));
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

    /** yml 基线 ∪ 数据字典动态规则；DB 异常时仅用基线并告警（不影响索引重建） */
    private List<String> loadDynamicRules() {
        try {
            return patternService.activePatterns();
        } catch (Exception e) {
            log.warn("读取动态排除规则失败，本次仅使用 yml 基线：{}", e.getMessage());
            return List.of();
        }
    }

    /** 排除判定：相对路径子串匹配（忽略大小写），外加导航/索引文件 */
    private boolean notExcluded(Path file, List<String> dynamicRules) {
        String rel = file.toString().replace('\\', '/').toLowerCase();
        if (matchesAny(rel, props.getExcludePatterns()) || matchesAny(rel, dynamicRules)) {
            return false;
        }
        // 导航/索引文件检索价值低，默认不索引
        String name = file.getFileName().toString().toLowerCase();
        return !name.equals("_index.md") && !name.endsWith("moc.md");
    }

    private static boolean matchesAny(String relPath, List<String> patterns) {
        if (patterns == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank() && relPath.contains(pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private List<NoteChunk> chunkFile(Path file, Path root) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            log.warn("读取失败，跳过：{}", file);
            return List.of();
        }

        String rel = root.relativize(file).toString().replace('\\', '/');
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
