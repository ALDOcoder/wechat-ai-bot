package com.example.wechataibot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Obsidian 知识库（关键词检索 RAG）配置，对应 application.yml 的 obsidian.*。
 */
@Component
@ConfigurationProperties(prefix = "obsidian")
public class ObsidianProperties {

    /**
     * Obsidian 库根目录（绝对路径）。留空或不存在 = 不启用 RAG。
     * 推荐用环境变量注入：OBSIDIAN_VAULT_PATH
     */
    private String vaultPath = "";

    /** 提问时最多拼入 Prompt 的笔记块数量 */
    private int topK = 5;

    /** 单个笔记块的最大字符数（超出按标题继续切块） */
    private int maxChunkChars = 600;

    /** 相对路径中包含这些片段的文件不索引（默认排除工具目录和私人聊天） */
    private List<String> excludePatterns = new ArrayList<>(List.of(
            ".mimocode", "node_modules", ".obsidian", "40-Life"));

    /**
     * Agent 写笔记的输出目录（相对 vault 根目录），仅 web 端聊天可用。
     * 留空 = 禁用笔记写入工具；写入的笔记立即加入检索索引。
     */
    private String agentOutputDir = "";

    public String getVaultPath() {
        return vaultPath;
    }

    public void setVaultPath(String vaultPath) {
        this.vaultPath = vaultPath == null ? "" : vaultPath.trim();
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getMaxChunkChars() {
        return maxChunkChars;
    }

    public void setMaxChunkChars(int maxChunkChars) {
        this.maxChunkChars = maxChunkChars;
    }

    public List<String> getExcludePatterns() {
        return excludePatterns;
    }

    public void setExcludePatterns(List<String> excludePatterns) {
        this.excludePatterns = excludePatterns;
    }

    public String getAgentOutputDir() {
        return agentOutputDir;
    }

    public void setAgentOutputDir(String agentOutputDir) {
        this.agentOutputDir = agentOutputDir == null ? "" : agentOutputDir.trim();
    }

    /** 是否启用 RAG */
    public boolean isEnabled() {
        return vaultPath != null && !vaultPath.isBlank();
    }
}
