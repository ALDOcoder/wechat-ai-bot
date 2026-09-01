package com.example.wechataibot.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 全量消息流水落库：收到的消息和 AI 回复各写一行 {@code message_log}，
 * 用 direction 区分 RECEIVED / SENT。
 */
@Repository
public class MessageLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public MessageLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(String conversationId, String scene, String direction,
                       String sender, String clientIp, String msgType,
                       String content, boolean useRag) {
        jdbcTemplate.update(
                "INSERT INTO message_log "
                        + "(conversation_id, scene, direction, sender, client_ip, msg_type, content, use_rag, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(3))",
                conversationId, scene, direction, sender, clientIp, msgType, content, useRag ? 1 : 0);
    }
}
