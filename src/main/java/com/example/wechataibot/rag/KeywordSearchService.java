package com.example.wechataibot.rag;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键词检索（轻量 BM25）：对内存中的笔记块按查询词打分，返回 top-k。
 *
 * <p>中文按“二元组”切词（如“金蝶苍穹” → 金蝶/蝶苍/苍穹），英文按单词；
 * 命中越多、越罕见的词，得分越高。零依赖，够用且快。
 */
@Service
public class KeywordSearchService {

    private static final Pattern LATIN = Pattern.compile("[a-zA-Z0-9_]{2,}");
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final VaultIndexService index;

    public KeywordSearchService(VaultIndexService index) {
        this.index = index;
    }

    /** 检索结果：笔记块 + 得分 */
    public record ScoredChunk(NoteChunk chunk, double score) {
        public String sourcePath() {
            return chunk.sourcePath();
        }

        public String heading() {
            return chunk.heading();
        }

        public String text() {
            return chunk.text();
        }
    }

    /** 按查询返回 top-k 笔记块 */
    public List<ScoredChunk> search(String query, int topK) {
        List<NoteChunk> all = index.getChunks();
        if (all.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }

        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        int n = all.size();
        Map<String, Integer> df = new HashMap<>();
        for (NoteChunk chunk : all) {
            List<String> tokens = new ArrayList<>(tokenize(chunk.text()));
            tokens.addAll(tokenize(chunk.heading()));
            for (String token : new java.util.LinkedHashSet<>(tokens)) {
                df.merge(token, 1, Integer::sum);
            }
        }

        double avgDl = all.stream()
                .mapToInt(c -> tokenize(c.text()).size() + tokenize(c.heading()).size())
                .average().orElse(1.0);
        avgDl = Math.max(1.0, avgDl);

        List<ScoredChunk> results = new ArrayList<>();
        for (NoteChunk chunk : all) {
            Map<String, Integer> tf = new HashMap<>();
            List<String> tokens = new ArrayList<>(tokenize(chunk.text()));
            tokens.addAll(tokenize(chunk.heading()));
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
            }
            double dl = tokens.size();

            double score = 0;
            for (String token : queryTokens) {
                int f = tf.getOrDefault(token, 0);
                if (f == 0) {
                    continue;
                }
                int d = df.getOrDefault(token, 1);
                double idf = Math.log((n - d + 0.5) / (d + 0.5) + 1);
                double norm = K1 * (1 - B + B * dl / avgDl);
                score += idf * (f * (K1 + 1)) / (f + norm);
            }
            if (score > 0) {
                results.add(new ScoredChunk(chunk, score));
            }
        }

        results.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return results.size() > topK ? results.subList(0, topK) : results;
    }

    /** 切词：英文单词 + 中文二元组 */
    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        Matcher m = LATIN.matcher(text.toLowerCase());
        while (m.find()) {
            tokens.add(m.group());
        }
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                cjk.append(c);
            }
        }
        if (cjk.length() == 1) {
            tokens.add(cjk.toString());
        } else {
            for (int i = 0; i + 1 < cjk.length(); i++) {
                tokens.add(cjk.substring(i, i + 2));
            }
        }
        return tokens;
    }
}
