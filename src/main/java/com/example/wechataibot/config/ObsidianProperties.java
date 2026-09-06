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

    /** 相对路径中包含这些片段的文件不索引且任何接口不可见（安全基线，不可通过 API 解锁） */
    private List<String> excludePatterns = new ArrayList<>(List.of(
            ".mimocode", "node_modules", ".obsidian"));

    /**
     * 受保护目录（相对 vault 根，子串匹配）：默认不索引、文件树里只显示折叠节点不露子文件名，
     * 持有效管理令牌后可经 /api/vault/protect/add 加入索引（持久化到 rag_unlock_path，可逆）。
     * 与安全基线的区别：保护目录可解锁，基线永远不可解锁。
     */
    private List<String> protectedDirs = new ArrayList<>(List.of("40-Life"));

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

    public List<String> getProtectedDirs() {
        return protectedDirs;
    }

    public void setProtectedDirs(List<String> protectedDirs) {
        this.protectedDirs = protectedDirs;
    }

    /** 是否启用 RAG */
    public boolean isEnabled() {
        return vaultPath != null && !vaultPath.isBlank();
    }
}
