package com.example.wechataibot.agent;

import com.example.wechataibot.config.ObsidianProperties;
import com.example.wechataibot.rag.VaultNoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Obsidian 笔记写入工具：让 web 端聊天的 agent 把用户明确要求保存的内容
 * 整理成 Markdown 笔记<b>草稿</b>，随回复返回 {@code pendingNotes}，由用户在网页
 * 确认卡片上点击「确认写入」后才真正落盘进知识库（两阶段写入，防提示注入直接落盘）。
 *
 * <p>安全护栏（见 {@link VaultNoteService}）：
 * <ul>
 *     <li>草稿只允许落在 {@code obsidian.agent-output-dir} 目录内，留空 = 工具不可用；</li>
 *     <li>文件名清洗：去除路径分隔符与非法字符、限长、空标题用时间戳兜底，杜绝路径穿越；</li>
 *     <li>永不覆盖：确认落盘时同名文件自动追加 -2 / -3 序号；</li>
 *     <li>单篇正文限长（默认 2 万字符），超出截断；草稿 10 分钟未确认自动过期。</li>
 * </ul>
 */
@Component
public class ObsidianNoteWriterTool {

    private static final Logger log = LoggerFactory.getLogger(ObsidianNoteWriterTool.class);

    private final ObsidianProperties props;
    private final VaultNoteService noteService;

    public ObsidianNoteWriterTool(ObsidianProperties props, VaultNoteService noteService) {
        this.props = props;
        this.noteService = noteService;
    }

    /**
     * 生成笔记草稿（不落盘）。由模型在用户明确要求保存时调用。
     *
     * @param title   笔记标题（用作文件名）
     * @param content Markdown 正文
     * @return 草稿结果说明（含计划路径），模型会转述给用户；确认卡片随响应 pendingNotes 下发
     */
    @Tool(description = "把用户明确要求保存/记录的内容整理成 Markdown 笔记草稿，经用户在网页上"
            + "确认后才会写入本地 Obsidian 知识库。仅当用户明确表示要“记成笔记/保存下来/记到知识库”"
            + "时才调用；普通聊天不要调用。")
    public String writeNote(
            @ToolParam(description = "笔记标题，将作为文件名，不要包含路径分隔符或特殊符号")
            String title,
            @ToolParam(description = "Markdown 格式的笔记正文")
            String content) {
        try {
            VaultNoteService.PendingNote note = noteService.draft(title, content);
            return "已生成笔记草稿（尚未写入）：" + note.path()
                    + "。请告知用户在网页的确认卡片上点击「确认写入」后才会保存。";
        } catch (IllegalStateException e) {
            log.info("笔记写入不可用：{}", e.getMessage());
            return "笔记写入未启用：" + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "草稿生成失败：" + e.getMessage();
        }
    }
}
