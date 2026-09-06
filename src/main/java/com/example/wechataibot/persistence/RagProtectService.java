package com.example.wechataibot.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 受保护目录"加入索引"状态的持久化（表 rag_unlock_path，一行一个目录）。
 *
 * <p>目录清单本身在 application.yml 的 {@code obsidian.protected-dirs}（如 40-Life），
 * 本表只记录其中"已被加入索引"的成员——重启不丢，这是索引成员资格而非临时解锁。
 */
@Service
public class RagProtectService {

    private final JdbcTemplate jdbcTemplate;

    public RagProtectService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 已加入索引的保护目录（原样大小写，匹配时由调用方忽略大小写） */
    public List<String> listJoined() {
        return jdbcTemplate.queryForList("SELECT path FROM rag_unlock_path ORDER BY created_at ASC", String.class);
    }

    /** 标记某目录为"已加入索引"（幂等） */
    public void join(String path) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO rag_unlock_path (path) VALUES (?)", path);
    }

    /** 移出索引（幂等） */
    public void leave(String path) {
        jdbcTemplate.update("DELETE FROM rag_unlock_path WHERE path = ?", path);
    }
}
