package com.example.wechataibot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信机器人配置，对应 application.yml 中的 wechat.bot.* 配置项。
 */
@Component
@ConfigurationProperties(prefix = "wechat.bot")
public class WeChatBotProperties {

    /**
     * 是否启用旧方案（网页版 wechat-api-uouo 机器人）。
     * 默认 false：推荐 Java 只做 AI 推理，由 Python + wxauto 负责微信收发。
     */
    private boolean enabled = false;

    /**
     * 是否自动登录（缓存登录态，二次启动免扫码）。
     * ⚠️ wechat-api 1.0.6 有 bug：无缓存时开启自动登录会重复登录两次，
     * 导致“你在其他地方登录了 WEB 版微信，再见”并被踢下线，请保持 false。
     */
    private boolean autoLogin = false;

    /** 是否在终端打印登录二维码 */
    private boolean showTerminal = true;

    /** 是否启用框架自带自动回复（建议 false，由 AI 逻辑统一处理） */
    private boolean autoReply = false;

    public boolean isAutoLogin() {
        return autoLogin;
    }

    public void setAutoLogin(boolean autoLogin) {
        this.autoLogin = autoLogin;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isShowTerminal() {
        return showTerminal;
    }

    public void setShowTerminal(boolean showTerminal) {
        this.showTerminal = showTerminal;
    }

    public boolean isAutoReply() {
        return autoReply;
    }

    public void setAutoReply(boolean autoReply) {
        this.autoReply = autoReply;
    }
}
