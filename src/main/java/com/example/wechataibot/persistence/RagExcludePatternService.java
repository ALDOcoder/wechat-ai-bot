package com.example.wechataibot.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RAG 排除规则数据字典：一行一条路径排除规则，供前端经 /api/rag/patterns 增删改。
 *
 * <p>生效语义 = application.yml 的 exclude-patterns 基线 ∪ 本表 enabled=1 的规则（并集）。
 * yml 基线不可通过 API 移除——前端删除规则永远不会意外放开隐私目录，
 * 想调整基线必须改 yml 并重启（见 {@code VaultIndexService} 的合并逻辑）。
 */
@Service
public class RagExcludePatternService {

    private final JdbcTemplate jdbcTemplate;

    public RagExcludePatternService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 全量规则（含停用的），按 id 正序 */
    public List<PatternRow> list() {
        return jdbcTemplate.query(
                "SELECT id, pattern, remark, enabled, updated_at FROM rag_exclude_pattern ORDER BY id ASC",
                (rs, i) -> new PatternRow(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getBoolean(4),
                        toLocal(rs.getTimestamp(5))));
    }

    /** 当前生效的规则（enabled=1），供索引重建时合并 */
    public List<String> activePatterns() {
        return jdbcTemplate.queryForList(
                "SELECT pattern FROM rag_exclude_pattern WHERE enabled = 1 ORDER BY id ASC", String.class);
    }

    /**
     * 新增规则（去重、非空、限长），成功后返回新行。
     *
     * @throws IllegalArgumentException pattern 为空/超长，或规则已存在
     */
    public PatternRow add(String pattern, String remark) {
        String p = normalize(pattern, "pattern");
        String r = remark == null ? "" : remark.trim();
        if (r.length() > 200) {
            throw new IllegalArgumentException("remark 超过 200 字");
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO rag_exclude_pattern (pattern, remark) VALUES (?, ?)", p, r);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("规则已存在：" + p);
        }
        return list().stream().filter(row -> row.pattern().equals(p)).findFirst().orElseThrow();
    }

    /** 更新启停/备注（允许部分字段），行不存在返回 null */
    public PatternRow update(long id, Boolean enabled, String remark) {
        String r = remark == null ? null : remark.trim();
        if (r != null && r.length() > 200) {
            throw new IllegalArgumentException("remark 超过 200 字");
        }
        if (enabled != null && r != null) {
            jdbcTemplate.update(
                    "UPDATE rag_exclude_pattern SET enabled = ?, remark = ? WHERE id = ?", enabled, r, id);
        } else if (enabled != null) {
            jdbcTemplate.update("UPDATE rag_exclude_pattern SET enabled = ? WHERE id = ?", enabled, id);
        } else if (r != null) {
            jdbcTemplate.update("UPDATE rag_exclude_pattern SET remark = ? WHERE id = ?", r, id);
        }
        return jdbcTemplate.query(
                "SELECT id, pattern, remark, enabled, updated_at FROM rag_exclude_pattern WHERE id = ?",
                (rs, i) -> new PatternRow(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getBoolean(4),
                        toLocal(rs.getTimestamp(5))),
                id).stream().findFirst().orElse(null);
    }

    /** 删除规则，返回是否真的删了 */
    public boolean delete(long id) {
        return jdbcTemplate.update("DELETE FROM rag_exclude_pattern WHERE id = ?", id) > 0;
    }

    /** 校验并归一：trim、非空、限长 200 */
    private static String normalize(String value, String field) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        if (v.length() > 200) {
            throw new IllegalArgumentException(field + " 超过 200 字");
        }
        return v;
    }

    private static LocalDateTime toLocal(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    /** 一条排除规则 */
    public record PatternRow(long id, String pattern, String remark, boolean enabled, LocalDateTime updatedAt) {
    }
}
