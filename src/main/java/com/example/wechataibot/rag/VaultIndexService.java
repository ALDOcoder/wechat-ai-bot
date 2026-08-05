package com.example.wechataibot.rag;

import com.example.wechataibot.config.ObsidianProperties;
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
 * <p>排除规则来自 {@link ObsidianProperties#getExcludePatterns()}（相对路径包含匹配），
 * 默认排除 .mimocode / node_modules / .obsidian / 40-Life（私人聊天）。
 */
@Service
public class VaultIndexService {

    private static final Logger log = LoggerFactory.getLogger(VaultIndexService.class);

    private final ObsidianProperties props;
    private volatile List<NoteChunk> chunks = List.of();
    private volatile int fileCount = 0;
    private volatile String lastError = "";

    public VaultIndexService(ObsidianProperties props) {
        this.props = props;
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
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".md"))
                    .filter(this::notExcluded)
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

    private boolean notExcluded(Path file) {
        String rel = file.toString().replace('\\', '/').toLowerCase();
        for (String pattern : props.getExcludePatterns()) {
            if (pattern != null && !pattern.isBlank() && rel.contains(pattern.toLowerCase())) {
                return false;
            }
        }
        // 导航/索引文件检索价值低，默认不索引
        String name = file.getFileName().toString().toLowerCase();
        return !name.equals("_index.md") && !name.endsWith("moc.md");
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
