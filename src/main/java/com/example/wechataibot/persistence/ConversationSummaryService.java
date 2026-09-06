package com.example.wechataibot.persistence;

import com.example.wechataibot.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 滚动摘要服务：把滑出记忆窗口的旧对话压缩成一份要点摘要，解决"窗口外失忆"。
 *
 * <p>记忆分两层配合工作：
 * <pre>
 *   每次请求发给模型 = 系统提示词 + 【早期对话摘要】 + 最近 maxMessages 条原文 + 当前输入
 * </pre>
 *
 * <p>工作方式：
 * <ul>
 *     <li>原料：message_log 全量流水（chat_memory 会被窗口覆盖裁剪，不能做原料）；</li>
 *     <li>游标：conversation_summary.last_message_id = 摘要已覆盖到 message_log.id 的位置，
 *         "id &gt; 游标 且 id &lt; 窗口边界"的区间就是待压缩的出窗积压；</li>
 *     <li>算法：增量 merge——新摘要 = LLM(旧摘要 + 这批出窗对话)，任何时刻只有一份摘要
 *         覆盖"会话开头 ~ 游标"；单次最多压 60 条防 prompt 过大，没追平下一轮继续；</li>
 *     <li>异步：回复成功后由 Controller 触发，单线程守护线程执行、按会话去重，
 *         生成失败只记 WARN，不影响正常回复，下次回复后会自动重试；</li>
 *     <li>模型：固定用免费智谱（zhipuChatModel），与会话当前用什么模型无关，控制成本。</li>
 * </ul>
 *
 * <p>"清空记忆"语义：游标推进到当前最新消息、摘要清空——清空之前的所有历史
 * （含旧摘要）都不再进入上下文，也不会被重新摘要回来。
 */
@Service
public class ConversationSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryService.class);

    /** 单条消息进摘要前的长度上限（超长粘贴内容截断，防止 prompt 失控） */
    private static final int MAX_MSG_CHARS_IN_PROMPT = 1000;

    /** 单次摘要的消息条数上限 */
    private static final int BATCH_LIMIT = 60;

    private static final String SUMMARY_PROMPT = """
            你是对话记忆压缩器。请将「旧摘要」与「新对话」融合成一份新的要点摘要：
            1. 保留对后续对话有用的事实：人物、时间、约定、决定、偏好、项目名与数字；
            2. 丢弃寒暄、重复和琐碎过程；
            3. 不超过 %d 字，用简洁的陈述句；
            4. 只输出摘要正文，不要任何解释或标题。

            【旧摘要】
            %s

            【新对话】
            %s""";

    private final JdbcTemplate jdbcTemplate;
    private final MemoryProperties memoryProperties;
    private final ChatClient summaryChatClient;

    /** 摘要专用单线程守护线程池：串行执行、不阻塞回复、不阻止 JVM 退出 */
    private final ExecutorService summaryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "conversation-summary");
        thread.setDaemon(true);
        return thread;
    });

    /** 正在摘要中的会话，防止同一会话重复排队 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public ConversationSummaryService(JdbcTemplate jdbcTemplate,
                                      MemoryProperties memoryProperties,
                                      @Qualifier("zhipuChatModel") OpenAiChatModel zhipuChatModel) {
        this.jdbcTemplate = jdbcTemplate;
        this.memoryProperties = memoryProperties;
        this.summaryChatClient = ChatClient.builder(zhipuChatModel).build();
    }

    /** 读当前摘要，无记录或为空返回 null（请求路径调用，一次索引 SELECT） */
    public String getSummary(String conversationId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT summary FROM conversation_summary WHERE conversation_id = ?",
                (rs, i) -> rs.getString(1),
                conversationId);
        String summary = rows.isEmpty() ? null : rows.get(0);
        return summary == null || summary.isBlank() ? null : summary;
    }

    /**
     * 回复成功后触发异步摘要检查：有出窗积压才真正调模型，没有则几条查询后返回。
     * 非阻塞、不抛异常，任何失败只影响摘要本身。
     */
    public void maybeSummarizeAsync(String conversationId) {
        if (!memoryProperties.isSummaryEnabled()
                || conversationId == null || conversationId.isBlank()) {
            return;
        }
        if (!inFlight.add(conversationId)) {
            return;
        }
        try {
            summaryExecutor.execute(() -> {
                try {
                    summarizeIfBacklog(conversationId);
                } catch (Exception e) {
                    log.warn("会话 [{}] 滚动摘要生成失败（下次回复后会重试）：{}",
                            conversationId, e.getMessage());
                } finally {
                    inFlight.remove(conversationId);
                }
            });
        } catch (Exception e) {
            inFlight.remove(conversationId);
            log.debug("摘要任务提交失败（服务关闭中）：{}", e.getMessage());
        }
    }

    /** "清空记忆"联动：游标推进到当前最新消息、摘要清空，旧历史不再进入上下文 */
    public void resetCursorAfterClear(String conversationId) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT MAX(id) FROM message_log WHERE conversation_id = ?",
                (rs, i) -> rs.getLong(1),
                conversationId);
        long maxId = rows.isEmpty() || rows.get(0) == null ? 0L : rows.get(0);
        save(conversationId, "", maxId);
        log.info("会话 [{}] 滚动摘要已随清空记忆重置（游标推进到 {}）", conversationId, maxId);
    }

    /** 删除会话联动：摘要行一起清 */
    public void delete(String conversationId) {
        jdbcTemplate.update("DELETE FROM conversation_summary WHERE conversation_id = ?", conversationId);
    }

    /** 同步摘要主逻辑：无积压直接返回，有则压缩一批并推进游标 */
    private void summarizeIfBacklog(String conversationId) {
        int window = memoryProperties.getMaxMessages();

        // 窗口边界 = 窗口内最早一条消息的 id；消息总数不足窗口时没有出窗积压
        Long boundary = findWindowBoundary(conversationId, window);
        if (boundary == null) {
            return;
        }
        long cursor = getCursor(conversationId);
        if (boundary - 1 <= cursor) {
            return;
        }

        List<LogRow> backlog = fetchBacklog(conversationId, cursor, boundary);
        if (backlog.isEmpty()) {
            // 区间里只剩系统兜底/清空确认等噪音行，推进游标避免每次空转（游标被并发重置时放弃）
            if (getCursor(conversationId) == cursor) {
                save(conversationId, "", boundary - 1);
            }
            return;
        }

        String oldSummary = getSummary(conversationId);
        String prompt = SUMMARY_PROMPT.formatted(
                memoryProperties.getSummaryMaxChars(),
                oldSummary == null ? "（无）" : oldSummary,
                buildTranscript(backlog));

        String reply = summaryChatClient.prompt()
                .user(prompt)
                .call()
                .content();
        String summary = truncate(reply);
        if (summary.isBlank()) {
            log.warn("会话 [{}] 摘要模型返回空内容，游标不推进（下次重试）", conversationId);
            return;
        }

        // 模型调用耗时较长，期间用户可能执行了"清空记忆"（游标被重置）——
        // 本次摘要基于已作废的旧对话，必须丢弃，否则清空的记忆会被"复活"
        if (getCursor(conversationId) != cursor) {
            log.info("会话 [{}] 游标已被并发重置（如清空记忆），丢弃本次摘要结果", conversationId);
            return;
        }

        long lastId = backlog.get(backlog.size() - 1).id();
        save(conversationId, summary, lastId);
        log.info("会话 [{}] 滚动摘要已更新：本次压缩 {} 条（id≤{}），摘要 {} 字",
                conversationId, backlog.size(), lastId, summary.length());
    }

    /** 窗口内最早一条消息的 id；消息总数不足窗口时返回 null */
    private Long findWindowBoundary(String conversationId, int window) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT id FROM message_log WHERE conversation_id = ? ORDER BY id DESC LIMIT 1 OFFSET ?",
                (rs, i) -> rs.getLong(1),
                conversationId, window - 1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long getCursor(String conversationId) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT last_message_id FROM conversation_summary WHERE conversation_id = ?",
                (rs, i) -> rs.getLong(1),
                conversationId);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    /**
     * 取"已出窗、未摘要"的旧消息（每会话一批最多 60 条）。
     * 过滤噪音行：兜底文案与清空确认（SENT 且 provider 为空）、空内容。
     */
    private List<LogRow> fetchBacklog(String conversationId, long cursor, long boundary) {
        return jdbcTemplate.query(
                "SELECT id, direction, content FROM message_log "
                        + "WHERE conversation_id = ? AND id > ? AND id < ? "
                        + "AND content <> '' AND NOT (direction = 'SENT' AND provider = '') "
                        + "ORDER BY id ASC LIMIT " + BATCH_LIMIT,
                (rs, i) -> new LogRow(rs.getLong(1), rs.getString(2), rs.getString(3)),
                conversationId, cursor, boundary);
    }

    /** 拼模型可读的对话文本：RECEIVED=用户、SENT=AI，单条超长截断 */
    private String buildTranscript(List<LogRow> backlog) {
        StringBuilder sb = new StringBuilder();
        for (LogRow row : backlog) {
            String speaker = "SENT".equals(row.direction()) ? "AI：" : "用户：";
            String content = row.content();
            if (content.length() > MAX_MSG_CHARS_IN_PROMPT) {
                content = content.substring(0, MAX_MSG_CHARS_IN_PROMPT) + "…";
            }
            sb.append(speaker).append(content).append('\n');
        }
        return sb.toString();
    }

    /** 摘要落库（覆盖写），游标随本次压缩推进 */
    private void save(String conversationId, String summary, long lastMessageId) {
        jdbcTemplate.update(
                "INSERT INTO conversation_summary (conversation_id, summary, last_message_id, updated_at) "
                        + "VALUES (?, ?, ?, NOW(3)) "
                        + "ON DUPLICATE KEY UPDATE summary = VALUES(summary), "
                        + "last_message_id = VALUES(last_message_id), updated_at = NOW(3)",
                conversationId, summary, lastMessageId);
    }

    /** 超出 summaryMaxChars 截断，控制每次请求注入的上下文量 */
    private String truncate(String text) {
        String trimmed = text == null ? "" : text.trim();
        int max = memoryProperties.getSummaryMaxChars();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /** message_log 行的只读视图 */
    private record LogRow(long id, String direction, String content) {
    }
}
