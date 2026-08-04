package com.example.wechataibot.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动器：Spring 容器启动完成后，在独立线程中启动微信机器人监听。
 */
@Component
public class WeChatBotStarter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WeChatBotStarter.class);

    private final AiWeChatBot aiWeChatBot;

    public WeChatBotStarter(AiWeChatBot aiWeChatBot) {
        this.aiWeChatBot = aiWeChatBot;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 默认关闭旧网页版方案：微信收发交给 Python + wxauto，
        // Java 端只负责通过 /api/reply 提供 AI 回复。
        if (!aiWeChatBot.isEnabled()) {
            log.info("wechat.bot.enabled=false，跳过网页版微信登录（旧方案）。");
            log.info("微信收发由 python/wechat_bridge_3x.py（或 _4x.py）负责，本服务提供 AI 接口。");
            return;
        }

        // start() 内部会登录并进入消息监听循环，会阻塞当前线程，
        // 因此放到独立的非守护线程中，避免阻塞 Spring 容器启动。
        Thread botThread = new Thread(() -> {
            try {
                log.info("正在启动微信机器人，请留意终端中的登录二维码……");
                aiWeChatBot.start();
            } catch (Exception e) {
                log.error("微信机器人启动或运行失败", e);
            }
        }, "wechat-bot-thread");
        botThread.setDaemon(false);
        botThread.start();
    }
}
