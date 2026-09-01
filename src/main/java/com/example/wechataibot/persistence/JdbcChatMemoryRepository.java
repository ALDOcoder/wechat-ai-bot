package com.example.wechataibot.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 MySQL 的 {@link ChatMemoryRepository} 实现。
 *
 * <p>每个会话一行，消息列表序列化成 JSON 存 {@code messages_json}：
 * <pre>[{"type":"USER","text":"你好"},{"type":"ASSISTANT","text":"你好呀"}]</pre>
 *
 * <p>{@code MessageWindowChatMemory} 在写入前会先按窗口裁剪，因此这里每次
 * {@code saveAll} 都是“整段覆盖写”，符合框架的调用约定（会话最多 20 条）。
 */
@Repository
public class JdbcChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcChatMemoryRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcChatMemoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> findConversationIds() {
        return jdbcTemplate.queryForList("SELECT conversation_id FROM chat_memory", String.class);
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT messages_json FROM chat_memory WHERE conversation_id = ?",
                rs -> {
                    List<String> result = new ArrayList<>();
                    while (rs.next()) {
                        result.add(rs.getString(1));
                    }
                    return result;
                },
                conversationId);
        return rows.isEmpty() ? List.of() : deserialize(rows.get(0));
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        jdbcTemplate.update(
                "INSERT INTO chat_memory (conversation_id, messages_json, updated_at) "
                        + "VALUES (?, ?, NOW(3)) "
                        + "ON DUPLICATE KEY UPDATE messages_json = VALUES(messages_json), updated_at = NOW(3)",
                conversationId, serialize(messages));
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        jdbcTemplate.update("DELETE FROM chat_memory WHERE conversation_id = ?", conversationId);
    }

    private String serialize(List<Message> messages) {
        List<Map<String, String>> list = new ArrayList<>();
        for (Message message : messages) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("type", message.getMessageType().name());
            item.put("text", message.getText() == null ? "" : message.getText());
            list.add(item);
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.error("序列化对话记忆失败，会话将按空记忆处理", e);
            return "[]";
        }
    }

    private List<Message> deserialize(String json) {
        try {
            List<Map<String, String>> list = objectMapper.readValue(
                    json, new TypeReference<List<Map<String, String>>>() {
                    });
            List<Message> messages = new ArrayList<>();
            for (Map<String, String> item : list) {
                String type = item.getOrDefault("type", "USER");
                String text = item.getOrDefault("text", "");
                switch (type) {
                    case "ASSISTANT" -> messages.add(new AssistantMessage(text));
                    case "SYSTEM" -> messages.add(new SystemMessage(text));
                    case "TOOL" -> log.warn("忽略 TOOL 类型的记忆消息");
                    default -> messages.add(new UserMessage(text));
                }
            }
            return messages;
        } catch (Exception e) {
            log.error("反序列化对话记忆失败，按空记忆处理", e);
            return List.of();
        }
    }
}
