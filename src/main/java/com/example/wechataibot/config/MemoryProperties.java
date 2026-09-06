package com.example.wechataibot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对话记忆配置，对应 application.yml 的 wechat.bot.memory.*。
 *
 * <p>记忆分两层：滑动窗口（{@code maxMessages} 条逐字原文）+ 滚动摘要
 * （窗口外的旧对话由免费模型压缩成要点，见 ConversationSummaryService）。
 */
@Component
@ConfigurationProperties(prefix = "wechat.bot.memory")
public class MemoryProperties {

    /** 每个会话保留的最近消息条数（用户 + AI 合计，约等于一半轮数） */
    private int maxMessages = 40;

    /** 是否启用滚动摘要（窗口外旧对话自动压缩，避免"窗外失忆"） */
    private boolean summaryEnabled = true;

    /** 摘要最大字符数（超出截断，控制每次请求注入的 token 量） */
    private int summaryMaxChars = 500;

    public int getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = Math.max(4, maxMessages);
    }

    public boolean isSummaryEnabled() {
        return summaryEnabled;
    }

    public void setSummaryEnabled(boolean summaryEnabled) {
        this.summaryEnabled = summaryEnabled;
    }

    public int getSummaryMaxChars() {
        return summaryMaxChars;
    }

    public void setSummaryMaxChars(int summaryMaxChars) {
        this.summaryMaxChars = Math.max(100, summaryMaxChars);
    }
}
