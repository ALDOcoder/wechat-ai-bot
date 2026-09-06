package com.example.wechataibot.agent;

import com.example.wechataibot.config.ObsidianProperties;
import com.example.wechataibot.rag.VaultIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Obsidian 笔记写入工具：让 web 端聊天的 agent 把用户明确要求保存的内容
 * 写成 Markdown 笔记存入知识库专属目录，写完立即重建索引（马上可被检索）。
 *
 * <p>安全护栏（防误写 / 提示注入）：
 * <ul>
 *     <li>只允许写入 {@code obsidian.agent-output-dir} 指定的库内目录，留空 = 工具不可用；</li>
 *     <li>文件名清洗：去除路径分隔符与非法字符、限长、空标题用时间戳兜底，杜绝路径穿越；</li>
 *     <li>永不覆盖：同名文件自动追加 -2 / -3 序号；</li>
 *     <li>单篇正文限长（默认 2 万字符），超出截断。</li>
 * </ul>
 *
 * <p>⚠️ 提示注入边界：模型可能被诱导写任意内容，但标题与目录受上述护栏约束，
 * 只能落在本目录的 .md 文件里；写库行为有 INFO 日志留痕。
 */
@Component
public class ObsidianNoteWriterTool {

    private static final Logger log = LoggerFactory.getLogger(ObsidianNoteWriterTool.class);

    /** 文件名（标题）最大长度 */
    private static final int MAX_TITLE_CHARS = 80;
    /** 单篇正文最大字符数 */
    private static final int MAX_CONTENT_CHARS = 20_000;
    /** 文件名非法字符：路径分隔符 + Windows 保留符号 + 控制字符 */
    private static final Pattern ILLEGAL_FILENAME = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    private final ObsidianProperties props;
    private final VaultIndexService indexService;

    public ObsidianNoteWriterTool(ObsidianProperties props, VaultIndexService indexService) {
        this.props = props;
        this.indexService = indexService;
    }

    /**
     * 写入一条笔记。由模型在用户明确要求保存时调用。
     *
     * @param title   笔记标题（用作文件名）
     * @param content Markdown 正文
     * @return 保存结果说明（含相对路径），模型会转述给用户
     */
    @Tool(description = "把用户明确要求保存/记录的内容写成 Markdown 笔记，保存到本地 Obsidian 知识库。"
            + "仅当用户明确表示要“记成笔记/保存下来/记到知识库”时才调用；普通聊天不要调用。")
    public String writeNote(
            @ToolParam(description = "笔记标题，将作为文件名，不要包含路径分隔符或特殊符号")
            String title,
            @ToolParam(description = "Markdown 格式的笔记正文")
            String content) {
        if (!props.isEnabled() || props.getAgentOutputDir().isBlank()) {
            return "笔记写入未启用（未配置 obsidian.agent-output-dir）。";
        }
        Path root = Paths.get(props.getVaultPath()).toAbsolutePath().normalize();
        Path dir = root.resolve(props.getAgentOutputDir()).normalize();
        if (!dir.startsWith(root) || dir.equals(root)) {
            log.warn("agent-output-dir 配置非法（必须在库根目录之下）：{}", props.getAgentOutputDir());
            return "笔记写入目录配置非法，已拒绝写入。";
        }

        String cleanTitle = sanitizeTitle(title);
        String heading = cleanTitle.isBlank() ? "Agent 笔记" : cleanTitle;

        try {
            Files.createDirectories(dir);
            Path target = uniqueTarget(dir, cleanTitle);
            String body = content == null ? "" : content.trim();
            if (body.length() > MAX_CONTENT_CHARS) {
                body = body.substring(0, MAX_CONTENT_CHARS) + "\n\n（内容超长，已截断）";
            }
            Files.writeString(target, "# " + heading + "\n\n" + body + "\n", StandardCharsets.UTF_8);

            // 立即重建索引（毫秒级），新笔记马上可被检索
            int chunks = indexService.rebuild();
            String rel = root.relativize(target).toString().replace('\\', '/');
            log.info("Agent 写入笔记：{}（索引重建后 {} 块）", rel, chunks);
            return "已保存笔记：" + rel + "，并已加入知识库索引。";
        } catch (Exception e) {
            log.error("Agent 写入笔记失败", e);
            return "笔记保存失败：" + e.getMessage();
        }
    }

    /** 文件名清洗：去非法字符、折叠空白、限长；空标题用时间戳兜底 */
    private static String sanitizeTitle(String title) {
        String t = title == null ? "" : title.trim();
        t = ILLEGAL_FILENAME.matcher(t).replaceAll(" ");
        t = t.replaceAll("\\s+", " ").trim();
        if (t.length() > MAX_TITLE_CHARS) {
            t = t.substring(0, MAX_TITLE_CHARS).trim();
        }
        if (t.isBlank()) {
            t = "笔记-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        }
        // 去掉结尾的点/空格（Windows 文件名不允许）
        return t.replaceAll("[. ]+$", "");
    }

    /** 目标文件：同名自动追加 -2 / -3 序号，永不覆盖 */
    private static Path uniqueTarget(Path dir, String title) {
        Path base = dir.resolve(title + ".md");
        if (!Files.exists(base)) {
            return base;
        }
        for (int i = 2; i < 1000; i++) {
            Path candidate = dir.resolve(title + "-" + i + ".md");
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        // 极端兜底：时间戳命名
        return dir.resolve(title + "-" + System.currentTimeMillis() + ".md");
    }
}
