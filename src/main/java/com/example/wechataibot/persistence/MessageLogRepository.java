package com.example.wechataibot.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 全量消息流水落库：收到的消息和 AI 回复各写一行 {@code message_log}，
 * 用 direction 区分 RECEIVED / SENT。会话管理接口也基于本表查询。
 */
@Repository
public class MessageLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public MessageLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(String conversationId, String scene, String direction,
                       String sender, String clientIp, String msgType,
                       String content, String provider, boolean useRag) {
        jdbcTemplate.update(
                "INSERT INTO message_log "
                        + "(conversation_id, scene, direction, sender, client_ip, msg_type, provider, content, use_rag, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(3))",
                conversationId, scene, direction, sender, clientIp, msgType, provider, content, useRag ? 1 : 0);
    }

    /** 列出 web 渠道的所有会话：标题取该会话第一条收到的消息，按最近活跃倒序 */
    public List<SessionSummary> listSessions() {
        String sql = "SELECT conversation_id, "
                + "(SELECT content FROM message_log m2 WHERE m2.conversation_id = m1.conversation_id "
                + "AND m2.direction = 'RECEIVED' ORDER BY m2.id LIMIT 1) AS title, "
                + "MAX(created_at) AS last_time, COUNT(*) AS msg_count "
                + "FROM message_log m1 WHERE scene = 'web' "
                + "GROUP BY conversation_id ORDER BY last_time DESC";
        return jdbcTemplate.query(sql, (rs, i) -> new SessionSummary(
                rs.getString("conversation_id"),
                rs.getString("title"),
                toLocal(rs.getTimestamp("last_time")),
                rs.getInt("msg_count")));
    }

    /** 某个会话的完整历史（按时间正序） */
    public List<ChatMessage> listByConversation(String conversationId) {
        String sql = "SELECT direction, sender, content, provider, use_rag, created_at "
                + "FROM message_log WHERE conversation_id = ? ORDER BY id ASC";
        return jdbcTemplate.query(sql,
                (rs, i) -> new ChatMessage(
                        rs.getString("direction"),
                        rs.getString("sender"),
                        rs.getString("content"),
                        rs.getString("provider"),
                        rs.getBoolean("use_rag"),
                        toLocal(rs.getTimestamp("created_at"))),
                conversationId);
    }

    /** 删除某个会话的全部流水（连同 AI 对话记忆一起清） */
    public int deleteByConversation(String conversationId) {
        return jdbcTemplate.update("DELETE FROM message_log WHERE conversation_id = ?", conversationId);
    }

    private static LocalDateTime toLocal(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    /** 会话列表条目 */
    public record SessionSummary(String conversationId, String title, LocalDateTime lastTime, int msgCount) {
    }

    /** 单条历史消息 */
    public record ChatMessage(String direction, String sender, String content,
                              String provider, boolean useRag, LocalDateTime createdAt) {
    }
}
