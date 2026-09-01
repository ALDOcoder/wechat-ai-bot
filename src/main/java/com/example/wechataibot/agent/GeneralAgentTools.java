package com.example.wechataibot.agent;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 通用工具：所有聊天对象都可以调用（由大模型自主决定是否调用）。
 *
 * <p>在 Spring AI 中，用 {@link Tool} 注解的方法会自动注册成 function calling 工具，
 * 模型在推理时会看到工具描述，自主决定要不要调用、传什么参数、何时调用。</p>
 */
@Component
public class GeneralAgentTools {

    @Tool(description = "获取当前的日期和时间。当用户询问“现在几点”、“今天几号”等时间类问题时调用。")
    public String getCurrentTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}