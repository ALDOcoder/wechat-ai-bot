package com.example.wechataibot.persistence;

import com.example.wechataibot.persistence.entity.ConversationSetting;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 会话设置的读写：没有记录时按默认值（zhipu / 禁止 deepseek）处理，
 * 只有前端切换或管理接口写值时才会落库。
 */
@Service
public class ConversationSettingService {

    private final JdbcTemplate jdbcTemplate;

    public ConversationSettingService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 查询某会话设置，无记录返回 null（调用方按默认值处理） */
    public ConversationSetting get(String conversationId) {
        return jdbcTemplate.query(
                "SELECT conversation_id, scene, preferred_model, allow_deepseek, updated_at "
                        + "FROM conversation_setting WHERE conversation_id = ?",
                rs -> rs.next() ? toSetting(rs) : null,
                conversationId);
    }

    /** 删除某会话的设置（删除会话时一并清理） */
    public void delete(String conversationId) {
        jdbcTemplate.update("DELETE FROM conversation_setting WHERE conversation_id = ?", conversationId);
    }

    /**
     * 更新或创建会话设置（支持部分字段：传 null 的字段保持不变 / 用默认值）。
     *
     * @param scene           渠道：friend / group / web，创建时必填，缺省按 web
     * @param preferredModel  偏好模型，仅接受 zhipu / deepseek，非法值归 zhipu
     * @param allowDeepseek   DeepSeek 授权，null = 不修改（创建时默认 false）
     */
    public ConversationSetting upsert(String conversationId, String scene,
                                      String preferredModel, Boolean allowDeepseek) {
        ConversationSetting row = get(conversationId);
        if (row == null) {
            String normalizedScene = (scene == null || scene.isBlank()) ? "web" : scene.trim();
            String normalizedModel = normalize(preferredModel);
            boolean allowed = Boolean.TRUE.equals(allowDeepseek);
            jdbcTemplate.update(
                    "INSERT INTO conversation_setting "
                            + "(conversation_id, scene, preferred_model, allow_deepseek, updated_at) "
                            + "VALUES (?, ?, ?, ?, NOW(3))",
                    conversationId, normalizedScene, normalizedModel, allowed ? 1 : 0);
            row = get(conversationId);
            return row;
        }
        String finalScene = (scene == null || scene.isBlank()) ? row.getScene() : scene.trim();
        String finalModel = (preferredModel == null) ? row.getPreferredModel() : normalize(preferredModel);
        boolean finalAllowed = (allowDeepseek == null)
                ? Boolean.TRUE.equals(row.getAllowDeepseek()) : allowDeepseek;
        jdbcTemplate.update(
                "UPDATE conversation_setting SET scene = ?, preferred_model = ?, "
                        + "allow_deepseek = ?, updated_at = NOW(3) WHERE conversation_id = ?",
                finalScene, finalModel, finalAllowed ? 1 : 0, conversationId);
        return get(conversationId);
    }

    private static String normalize(String model) {
        return "deepseek".equalsIgnoreCase(model) ? "deepseek" : "zhipu";
    }

    private static ConversationSetting toSetting(java.sql.ResultSet rs) throws java.sql.SQLException {
        ConversationSetting setting = new ConversationSetting();
        setting.setConversationId(rs.getString("conversation_id"));
        setting.setScene(rs.getString("scene"));
        setting.setPreferredModel(rs.getString("preferred_model"));
        setting.setAllowDeepseek(rs.getBoolean("allow_deepseek"));
        java.sql.Timestamp ts = rs.getTimestamp("updated_at");
        setting.setUpdatedAt(ts == null ? null : ts.toLocalDateTime());
        return setting;
    }
}
