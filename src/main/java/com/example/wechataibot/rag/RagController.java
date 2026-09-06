package com.example.wechataibot.rag;

import com.example.wechataibot.config.ObsidianProperties;
import com.example.wechataibot.persistence.RagExcludePatternService;
import com.example.wechataibot.persistence.RagExcludePatternService.PatternRow;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * RAG 管理/调试接口：
 * <ul>
 *     <li>GET  /api/rag/status   —— 索引状态（是否启用、多少文件、多少笔记块）</li>
 *     <li>POST /api/rag/refresh  —— 重建索引（笔记变更后手动刷新）</li>
 *     <li>GET  /api/rag/search?q=... —— 调试检索，返回命中的笔记块</li>
 *     <li>GET/POST/PUT/DELETE /api/rag/patterns —— 排除规则数据字典（前端管理），
 *         变更后自动重建索引立即生效；yml 基线规则不可通过 API 移除</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final ObsidianProperties props;
    private final VaultIndexService index;
    private final KeywordSearchService search;
    private final RagExcludePatternService patterns;

    public RagController(ObsidianProperties props, VaultIndexService index,
                         KeywordSearchService search, RagExcludePatternService patterns) {
        this.props = props;
        this.index = index;
        this.search = search;
        this.patterns = patterns;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "enabled", props.isEnabled(),
                "vaultPath", props.getVaultPath(),
                "files", index.getFileCount(),
                "chunks", index.getChunks().size(),
                "lastError", index.getLastError());
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        int chunks = index.rebuild();
        return Map.of(
                "enabled", props.isEnabled(),
                "files", index.getFileCount(),
                "chunks", chunks,
                "lastError", index.getLastError());
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("q") String query,
                                      @RequestParam(value = "top", defaultValue = "5") int top) {
        List<KeywordSearchService.ScoredChunk> results = search.search(query, top);
        List<Map<String, Object>> items = results.stream()
                .map(r -> Map.<String, Object>of(
                        "path", r.sourcePath(),
                        "heading", r.heading() == null ? "" : r.heading(),
                        "score", Math.round(r.score() * 1000) / 1000.0,
                        "snippet", snippet(r.text())))
                .toList();
        return Map.of("query", query, "count", items.size(), "results", items);
    }

    private String snippet(String text) {
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    // ==================== 排除规则数据字典（前端管理） ====================

    /** 规则列表：basePatterns = yml 基线（不可 API 移除），patterns = 数据字典 */
    @GetMapping("/patterns")
    public Map<String, Object> listPatterns() {
        return Map.of(
                "basePatterns", props.getExcludePatterns(),
                "patterns", patterns.list().stream().map(RagController::toMap).toList());
    }

    /** 新增规则并自动重建索引，立即生效 */
    @PostMapping("/patterns")
    public ResponseEntity<?> addPattern(@RequestBody(required = false) AddPatternRequest body) {
        try {
            PatternRow row = patterns.add(body == null ? null : body.pattern(),
                    body == null ? null : body.remark());
            return ResponseEntity.ok(rebuildResult("row", toMap(row)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /** 更新启停/备注并自动重建索引 */
    @PutMapping("/patterns/{id}")
    public ResponseEntity<?> updatePattern(@PathVariable long id,
                                           @RequestBody(required = false) UpdatePatternRequest body) {
        try {
            PatternRow row = patterns.update(id,
                    body == null ? null : body.enabled(), body == null ? null : body.remark());
            if (row == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(rebuildResult("row", toMap(row)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /** 删除规则并自动重建索引（仅数据字典内的规则，yml 基线不受影响） */
    @DeleteMapping("/patterns/{id}")
    public ResponseEntity<?> deletePattern(@PathVariable long id) {
        if (!patterns.delete(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(rebuildResult("deleted", true));
    }

    private Map<String, Object> rebuildResult(String key, Object value) {
        int chunks = index.rebuild();
        return Map.of(
                key, value,
                "files", index.getFileCount(),
                "chunks", chunks,
                "lastError", index.getLastError());
    }

    private static Map<String, Object> toMap(PatternRow row) {
        return Map.of(
                "id", row.id(),
                "pattern", row.pattern(),
                "remark", row.remark(),
                "enabled", row.enabled(),
                "updatedAt", row.updatedAt() == null ? "" : row.updatedAt().toString());
    }

    /** 新增规则请求体 */
    public record AddPatternRequest(String pattern, String remark) {
    }

    /** 更新规则请求体（允许部分字段） */
    public record UpdatePatternRequest(Boolean enabled, String remark) {
    }
}
