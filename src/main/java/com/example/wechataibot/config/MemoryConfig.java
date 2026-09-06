package com.example.wechataibot.config;

import com.example.wechataibot.persistence.JdbcChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对话记忆配置：让 AI 记住每个聊天对象的上下文（多轮对话）。
 *
 * <p>实现：Spring AI 官方的 {@link MessageWindowChatMemory}（滑动窗口记忆），
 * 底层仓库换成 MySQL 实现（{@link JdbcChatMemoryRepository}），
 * 重启 Java 服务后对话记忆不丢失。
 * 每个聊天对象一个会话（conversationId = 微信里的聊天对象名 / web:IP），
 * 只保留最近 {@code maxMessages} 条消息，防止上下文无限膨胀、也控制 token 成本。
 *
 * <p>窗口大小可在 application.yml 的 {@code wechat.bot.memory.max-messages} 调整；
 * 滑出窗口的旧对话不会丢——由滚动摘要服务压缩成要点后注入 system prompt
 * （见 {@link ConversationSummaryService}，可用 summary-enabled 关闭）。
 *
 * <p>⚠️ 说明：
 * <ul>
 *     <li>记忆持久化在 MySQL 的 chat_memory 表（messages_json 列）；</li>
 *     <li>发送“清空记忆”可手动清空某个会话的记忆（见 WechatBridgeController）。</li>
 * </ul>
 */
@Configuration
public class MemoryConfig {

    @Bean
    public ChatMemory chatMemory(JdbcChatMemoryRepository chatMemoryRepository,
                                 MemoryProperties memoryProperties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(memoryProperties.getMaxMessages())
                .build();
    }
}
