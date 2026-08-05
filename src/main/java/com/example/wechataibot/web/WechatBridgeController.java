package com.example.wechataibot.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 桥接 Controller：Java 逻辑端对 Python 微信端（wxauto）暴露的 HTTP 接口。
 *
 * <p>工作方式：
 * <pre>
 *   Python(wxauto) 收到微信新消息
 *       └─ POST /api/reply  {"sender":"张三","content":"你好"}
 *             └─ Spring AI ChatClient + 记忆顾问调用远端 HTTPS 大模型
 *       ┌─ 返回 {"reply":"你好呀！"}
 *   Python(wxauto) 把 reply 发回微信
 * </pre>
 *
 * <p>多轮记忆：每个聊天对象一个会话（conversationId = sender），
 * 由 {@link MessageChatMemoryAdvisor} 自动携带最近的聊天历史，
 * 窗口大小见 {@code MemoryConfig}（默认最近 20 条消息 ≈ 10 轮）。
 * 发送“清空记忆”可清空当前会话的记忆。
 *
 * <p>⚠️ 风险提示（务必阅读）：
 * <ul>
 *     <li>个人微信自动化违反微信用户协议，账号存在被限制登录、封禁的风险，
 *         请务必使用【测试小号】运行，不要使用主号；</li>
 *     <li>聊天内容（含历史记忆）会发送到第三方大模型服务，注意保护隐私，
 *         不要输入敏感信息；</li>
 *     <li>本接口没有鉴权，只建议在本机（127.0.0.1）使用，不要暴露到公网。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class WechatBridgeController {

    private static final Logger log = LoggerFactory.getLogger(WechatBridgeController.class);

    /** 给大模型的系统提示词，可自行调整人设 */
    private static final String SYSTEM_PROMPT =
            "你是一个友善、简洁的微信聊天助手，请用自然的中文与用户聊天，回答尽量简短（一般不超过 100 字）。";

    /** 发送这条消息会清空当前会话的记忆 */
    private static final String CLEAR_MEMORY_COMMAND = "清空记忆";

    private final ChatMemory chatMemory;
    private final ChatClient chatClient;

    /**
     * @param chatModel  Spring AI 自动注入的 OpenAI 兼容 ChatModel
     *                   （由 spring.ai.openai.base-url / api-key 配置驱动，远端 HTTPS 调用）
     * @param chatMemory 对话记忆（MemoryConfig 中定义的滑动窗口实现）
     */
    public WechatBridgeController(ChatModel chatModel, ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /** 健康检查：Python 端启动时可先调用此接口确认 Java 服务在线 */
    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("ok");
    }

    /**
     * 核心接口：接收微信文本消息，调用远端大模型，返回 AI 回复（带多轮记忆）。
     *
     * @param request JSON 请求体：{"sender": "发送者昵称（可选，也是会话 ID）", "content": "消息内容（必填）"}
     * @return JSON 响应体：{"reply": "AI 回复内容"}
     */
    @PostMapping("/reply")
    public ResponseEntity<ReplyResponse> reply(@RequestBody(required = false) ReplyRequest request) {
        if (request == null || request.content() == null || request.content().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(new ReplyResponse("消息内容为空，无法处理。"));
        }

        String text = request.content().trim();
        String sender = request.sender() == null ? "" : request.sender().trim();
        // 会话 ID：一个微信聊天对象 = 一个会话；空发送者用 default 兜底
        String conversationId = sender.isEmpty() ? "default" : sender;

        // 单独的消息流水日志：记录收到什么消息（logs/messages.txt）
        MessageTraceLogger.received(sender, text);

        // 记忆控制命令：清空当前会话的上下文
        if (CLEAR_MEMORY_COMMAND.equals(text)) {
            chatMemory.clear(conversationId);
            String ack = "好的，已清空我们之前的聊天记忆，重新开始聊吧。";
            MessageTraceLogger.sent(sender, ack);
            log.info("已清空会话 [{}] 的对话记忆", conversationId);
            return ResponseEntity.ok(new ReplyResponse(ack));
        }

        try {
            // 模型与温度等参数在 application.yml 的 spring.ai.openai.* 中配置；
            // MessageChatMemoryAdvisor 会自动带上该会话的历史消息。
            String reply = chatClient.prompt()
                    .user(u -> u.text(text).param("conversationId", conversationId))
                    .call()
                    .content();

            if (reply == null || reply.isBlank()) {
                reply = "抱歉，AI 没有返回内容，请再试一次。";
            }
            // 单独的消息流水日志：记录要发送什么消息（logs/messages.txt）
            MessageTraceLogger.sent(sender, reply);
            log.info("收到 [{}] 的消息（{} 字），AI 回复（{} 字）", sender, text.length(), reply.trim().length());
            return ResponseEntity.ok(new ReplyResponse(reply.trim()));
        } catch (Exception e) {
            // AI 接口异常时不要静默丢消息：返回 200 + 友好兜底文案，Python 端会把提示发回微信
            MessageTraceLogger.sent(sender, "抱歉，AI 服务暂时不可用，请稍后再试。");
            log.error("调用远端大模型失败，会话 [{}]，原始消息：{}", conversationId, text, e);
            return ResponseEntity.ok(new ReplyResponse("抱歉，AI 服务暂时不可用，请稍后再试。"));
        }
    }

    /** 请求体 */
    public record ReplyRequest(String sender, String content) {
    }

    /** 响应体 */
    public record ReplyResponse(String reply) {
    }

    /** 健康检查响应 */
    public record HealthResponse(String status) {
    }
}
