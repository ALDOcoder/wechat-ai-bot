package com.example.wechataibot.web;

import com.example.wechataibot.agent.GeneralAgentTools;
import com.example.wechataibot.agent.KnowledgeAgentTool;
import com.example.wechataibot.config.ObsidianProperties;
import com.example.wechataibot.persistence.MessageLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 桥接 Controller：Java 逻辑端对 Python 微信端（wxauto）暴露的 HTTP 接口。
 *
 * <p>工作方式：
 * <pre>
 *   Python(wxauto) 收到微信新消息
 *       └─ POST /api/reply  {"sender":"张三","content":"你好"}
 *             └─ Spring AI Agent（ChatClient + Function Calling）
 *                模型自主决定是否调用工具（知识库检索 / 当前时间）
 *       ┌─ 返回 {"reply":"你好呀！"}
 *   Python(wxauto) 把 reply 发回微信
 * </pre>
 *
 * <p><b>Agent 化改造</b>：不再是一问一答的固定管线，而是借助 Spring AI 的
 * {@link ChatClient} 工具调用（function calling）能力，让大模型自己决定下一步：
 * <ul>
 *     <li>问题涉及本地知识 → 自主调用 {@code searchNotes} 工具（Agentic RAG）；</li>
 *     <li>问时间 → 自主调用 {@code getCurrentTime} 工具；</li>
 *     <li>普通闲聊 → 直接回答，不产生检索成本。</li>
 * </ul>
 * Spring AI 的 {@code ChatClient} 会自动完成“模型 → 决定调工具 → 执行工具 →
 * 把结果回填 → 模型再作答”的循环。
 *
 * <p>多轮记忆沿用 {@link ChatMemory}（滑动窗口，见 {@code MemoryConfig}），
 * 每个聊天对象一个会话（私聊 conversationId = 好友名，群聊 = group:群名:发送者，
 * web 端 = web:客户端IP），持久化在 MySQL。知识库工具按
 * {@code rag_friends} 白名单（Python 端 {@code useRag} 标记）动态启用。
 * 收到/发送的消息同时写入 message_log 表（方向字段区分）。
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
            "你是一个友善、简洁的微信聊天助手，请用自然的中文与用户聊天，回答尽量简短（一般不超过 100 字）。"
            + "你可以自主决定是否调用工具来获取额外信息，再据此回答。";

    /** 发送这条消息会清空当前会话的记忆 */
    private static final String CLEAR_MEMORY_COMMAND = "清空记忆";

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final GeneralAgentTools generalTools;
    private final KnowledgeAgentTool knowledgeTool;
    private final ObsidianProperties obsidianProperties;
    private final MessageLogRepository messageLogRepository;

    public WechatBridgeController(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory,
                                  GeneralAgentTools generalTools, KnowledgeAgentTool knowledgeTool,
                                  ObsidianProperties obsidianProperties,
                                  MessageLogRepository messageLogRepository) {
        this.generalTools = generalTools;
        this.knowledgeTool = knowledgeTool;
        this.obsidianProperties = obsidianProperties;
        this.messageLogRepository = messageLogRepository;
        this.chatMemory = chatMemory;
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    /** 健康检查：Python 端启动时可先调用此接口确认 Java 服务在线 */
    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("ok");
    }

    /**
     * 核心接口：接收微信文本消息，由 Agent 自主调用工具后调用远端大模型，返回 AI 回复。
     *
     * @param request     JSON 请求体：{"sender": "发送者", "content": "消息内容", "useRag": "是否允许知识库", "scene": "friend/group/web", "chat": "群名（群聊时）"}
     * @param httpRequest 原始 HTTP 请求，用于取客户端 IP（web 场景会话键）
     * @return JSON 响应体：{"reply": "AI 回复内容", "ragUsed": "本次是否允许了知识库工具"}
     */
    @PostMapping("/reply")
    public ResponseEntity<ReplyResponse> reply(@RequestBody(required = false) ReplyRequest request,
                                               HttpServletRequest httpRequest) {
        if (request == null || request.content() == null || request.content().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(new ReplyResponse("消息内容为空，无法处理。", false));
        }

        String text = request.content().trim();
        String sender = request.sender() == null ? "" : request.sender().trim();
        String clientIp = resolveClientIp(httpRequest);

        // 渠道：scene 缺省时做兼容判断（web 前端曾用 sender=web）
        String sceneRaw = request.scene() == null ? "" : request.scene().trim().toLowerCase();
        String scene = switch (sceneRaw) {
            case "web" -> "web";
            case "group" -> "group";
            case "friend" -> "friend";
            default -> "web".equals(sender) ? "web" : "friend";
        };

        // 会话 ID：一个聊天对象 = 一个会话，按渠道拼键
        String conversationId;
        if ("web".equals(scene)) {
            conversationId = "web:" + clientIp;
        } else if ("group".equals(scene)) {
            String chatName = request.chat() == null ? "" : request.chat().trim();
            conversationId = "group:" + (chatName.isEmpty() ? sender : chatName) + ":" + sender;
        } else {
            conversationId = sender.isEmpty() ? "default" : sender;
        }

        // 是否允许本次会话使用知识库工具（由 Python 端按 rag_friends 白名单决定）
        boolean useRag = request.useRag() != null && request.useRag();
        boolean ragEnabled = useRag && obsidianProperties.isEnabled();

        MessageTraceLogger.received(sender, text);
        safeLogMessage(conversationId, scene, "RECEIVED", sender, clientIp, text, ragEnabled);

        // 记忆控制命令：清空当前会话的上下文
        if (CLEAR_MEMORY_COMMAND.equals(text)) {
            chatMemory.clear(conversationId);
            String ack = "好的，已清空我们之前的聊天记忆，重新开始聊吧。";
            MessageTraceLogger.sent(sender, ack);
            safeLogMessage(conversationId, scene, "SENT", sender, clientIp, ack, ragEnabled);
            log.info("已清空会话 [{}] 的对话记忆", conversationId);
            return ResponseEntity.ok(new ReplyResponse(ack, false));
        }

        // 组装本次可用的工具：通用工具始终可用，知识库工具按 RAG 开关动态追加
        List<Object> tools = new ArrayList<>();
        tools.add(generalTools);
        if (ragEnabled) {
            tools.add(knowledgeTool);
        }

        try {
            // 组装 Prompt：系统提示 + 该会话的历史记忆 + 当前消息
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(SYSTEM_PROMPT));
            messages.addAll(chatMemory.get(conversationId));
            messages.add(new UserMessage(text));

            // Agent 化调用：模型在生成过程中自主决定是否调用已注册的工具，
            // Spring AI 自动完成「工具调用 → 结果回填 → 再作答」的循环。
            String reply = chatClient.prompt()
                    .messages(messages)
                    .tools(tools.toArray())
                    .call()
                    .content();

            if (reply == null || reply.isBlank()) {
                reply = "抱歉，AI 没有返回内容，请再试一次。";
            }
            // 写回记忆：用户消息 + AI 回复（MessageWindowChatMemory 会自动裁剪窗口）
            chatMemory.add(conversationId,
                    List.of(new UserMessage(text), new AssistantMessage(reply)));
            MessageTraceLogger.sent(sender, reply);
            safeLogMessage(conversationId, scene, "SENT", sender, clientIp, reply, ragEnabled);
            log.info("收到 [{}] 的消息（{} 字），Agent 回复（{} 字）", sender, text.length(), reply.trim().length());
            return ResponseEntity.ok(new ReplyResponse(reply.trim(), ragEnabled));
        } catch (Exception e) {
            // AI 接口异常时不要静默丢消息：返回 200 + 友好兜底文案，Python 端会把提示发回微信
            log.error("调用远端大模型失败，会话 [{}]，原始消息：{}", conversationId, text, e);
            String fallback = "抱歉，AI 服务暂时不可用，请稍后再试。";
            MessageTraceLogger.sent(sender, fallback);
            safeLogMessage(conversationId, scene, "SENT", sender, clientIp, fallback, ragEnabled);
            return ResponseEntity.ok(new ReplyResponse(fallback, ragEnabled));
        }
    }

    /** 消息流水落库，失败只告警、不影响正常回复 */
    private void safeLogMessage(String conversationId, String scene, String direction,
                                String sender, String clientIp, String content, boolean ragEnabled) {
        try {
            messageLogRepository.insert(conversationId, scene, direction, sender, clientIp, "text", content, ragEnabled);
        } catch (Exception e) {
            log.warn("消息流水落库失败（不影响回复）：{}", e.getMessage());
        }
    }

    /** 取客户端 IP：优先 X-Forwarded-For（Vite 代理已开启 xfwd），否则 remoteAddr */
    private String resolveClientIp(HttpServletRequest httpRequest) {
        String forwarded = httpRequest.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return httpRequest.getRemoteAddr();
    }

    /** 请求体 */
    public record ReplyRequest(String sender, String content, Boolean useRag, String scene, String chat) {
    }

    /** 响应体 */
    public record ReplyResponse(String reply, boolean ragUsed) {
    }

    /** 健康检查响应 */
    public record HealthResponse(String status) {
    }
}
