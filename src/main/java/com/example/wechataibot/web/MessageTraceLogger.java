package com.example.wechataibot.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 消息流水记录：把“收到什么消息 / 发送什么消息”单独写到 logs/messages.txt，
 * 与完整运行日志（logs/wechat-ai-bot.txt）区分开，方便只看对话记录。
 *
 * <p>格式示例：
 * <pre>
 * 2026-08-04 20:30:00 | 收到 | 来自: 姐姐 | 内容: 你好
 * 2026-08-04 20:30:01 | 发送 | 发给: 姐姐 | 内容: 你好呀！有什么可以帮你？
 * </pre>
 */
public final class MessageTraceLogger {

    private static final Logger log = LoggerFactory.getLogger(MessageTraceLogger.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Path FILE = Paths.get("logs", "messages.txt");

    private MessageTraceLogger() {
    }

    /** 记录一条收到的微信消息 */
    public static void received(String sender, String content) {
        write("收到 | 来自: " + nvl(sender) + " | 内容: " + nvl(content));
    }

    /** 记录一条将要发送（回给微信）的消息 */
    public static void sent(String who, String content) {
        write("发送 | 发给: " + nvl(who) + " | 内容: " + nvl(content));
    }

    private static synchronized void write(String body) {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(
                    FILE,
                    LocalDateTime.now().format(TS) + " | " + body + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("写入消息日志失败：{}", FILE, e);
        }
    }

    /** 去掉换行等控制字符，保证日志文件里一行一条消息 */
    private static String nvl(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ");
    }
}
