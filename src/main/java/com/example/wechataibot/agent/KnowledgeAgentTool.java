package com.example.wechataibot.agent;

import com.example.wechataibot.config.ObsidianProperties;
import com.example.wechataibot.rag.KeywordSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 知识库检索工具：让大模型自主决定何时检索本地 Obsidian 笔记（Agentic RAG）。
 *
 * <p>相比旧的固定管线（提问→必定检索→必定拼进 Prompt），这里把“检索”变成模型
 * 可调用的一个工具：模型自己判断问题是否与本地知识相关、决定要不要调用、传什么关键词。
 * 只有触发该工具才消耗额外的检索 token，普通闲聊完全不产生检索成本。</p>
 */
@Component
public class KnowledgeAgentTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAgentTool.class);

    private final KeywordSearchService searchService;
    private final ObsidianProperties props;

    public KnowledgeAgentTool(KeywordSearchService searchService, ObsidianProperties props) {
        this.searchService = searchService;
        this.props = props;
    }

    /**
     * 知识库检索。模型收到问题后，由它自己决定是否调用此工具。
     *
     * @param query 想检索的关键词或问题
     * @return 命中的笔记片段（带来源文件与标题），无命中返回提示
     */
    @Tool(description = "在本地 Obsidian 笔记库中检索与用户问题相关的知识。"
            + "当用户询问内容涉及你机密的私人笔记、技术知识点、学习笔记时，应先调用此工具获取资料再回答。")
    public String searchNotes(String query) {
        var hits = searchService.search(query, props.getTopK());
        if (hits == null || hits.isEmpty()) {
            log.info("知识库检索无命中：query=[{}]", query);
            return "本地笔记库中没有找到相关内容。";
        }
        StringBuilder sb = new StringBuilder("检索到以下笔记片段：\n");
        for (int i = 0; i < hits.size(); i++) {
            var hit = hits.get(i);
            sb.append(i + 1).append(". 【").append(hit.sourcePath());
            if (hit.heading() != null && !hit.heading().isBlank()) {
                sb.append(" / ").append(hit.heading());
            }
            sb.append("】\n").append(hit.text()).append("\n\n");
        }
        log.info("知识库检索命中 {} 条，query=[{}]", hits.size(), query);
        return sb.toString();
    }
}