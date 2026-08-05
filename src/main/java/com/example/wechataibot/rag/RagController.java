package com.example.wechataibot.rag;

import com.example.wechataibot.config.ObsidianProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
 * </ul>
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final ObsidianProperties props;
    private final VaultIndexService index;
    private final KeywordSearchService search;

    public RagController(ObsidianProperties props, VaultIndexService index, KeywordSearchService search) {
        this.props = props;
        this.index = index;
        this.search = search;
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
}
