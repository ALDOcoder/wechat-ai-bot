package com.example.wechataibot.web;

import com.example.wechataibot.agent.GeneralAgentTools;
import com.example.wechataibot.agent.KnowledgeAgentTool;
import com.example.wechataibot.config.ObsidianProperties;
import com.example.wechataibot.persistence.ConversationSettingService;
import com.example.wechataibot.persistence.MessageLogRepository;
import com.example.wechataibot.persistence.MessageLogRepository.ChatMessage;
import com.example.wechataibot.persistence.MessageLogRepository.SessionSummary;
import com.example.wechataibot.persistence.entity.ConversationSetting;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 桥接 Controller：Java 逻辑端对 Python 微信端（wxauto）与 Web 前端暴露的 HTTP 接口。
 *
 * <p>工作方式：
 * <pre>
 *   Python(wxauto) / Web 前端 收到消息
 *       └─ POST /api/reply  {"sender":"张三","content":"你好","scene":"friend|web","sessionId":"...","provider":"zhipu|glm4flash|deepseek"}
 *             └─ Spring AI Agent（ChatClient + Function Calling）
 *                模型自主决定是否调用工具（知识库检索 / 当前时间）
 *       ┌─ 返回 {"reply":"你好呀！","ragUsed":false,"provider":"zhipu"}
 * </pre>
 *
 * <p>会话键（conversationId）规则：
 * <ul>
 *     <li>微信私聊：conversationId = 好友名，scene=friend</li>
 *     <li>微信群聊：conversationId = group:群名:发送者，scene=group</li>
 *     <li>Web 多会话：conversationId = web:&lt;sessionId&gt;，scene=web（前端每个会话一个独立 id）</li>
 *     <li>Web 兜底（未传 sessionId）：conversationId = web:&lt;客户端IP&gt;</li>
 * </ul>
 * 会话列表 / 历史 / 删除通过 /api/sessions* 提供；模型偏好与 DeepSeek 授权
 * 通过 /api/conversations/{id}/setting 提供（落库 conversation_setting）。
 *
 * <p>模型选择与权限（详见 docs/MODEL-SWITCH.md）：
 * <ul>
 *     <li>provider 缺省 = 取会话偏好（preferred_model），再缺省 zhipu（免费）；
 *         可选 zhipu（GLM-4.7-Flash）/ glm4flash（GLM-4-Flash，免费备选）/ deepseek（付费）；</li>
 *     <li>请求 deepseek 但会话未授权（allow_deepseek=0）→ 强制回落 zhipu；</li>
 *     <li>deepseek 调用失败 → 自动回落 zhipu 一次，响应标注实际 provider；</li>
 *     <li>对话记忆（chat_memory）各模型共享，不按模型拆分。</li>
 * </ul>
 *
 * <p>⚠️ 风险提示（务必阅读）：
 * <ul>
 *     <li>个人微信自动化违反微信用户协议，账号存在被限制登录、封禁的风险，
 *         请务必使用【测试小号】运行，不要使用主号；</li>
 *     <li>聊天内容（含历史记忆）会发送到第三方大模型服务，注意保护隐私，
 *         不要输入敏感信息；</li>
 *     <li>DeepSeek 是付费模型，白名单之外一律回落免费模型，避免 key 被滥用烧钱；</li>
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

    /** 可用模型标识 */
    private static final String PROVIDER_ZHIPU = "zhipu";
    private static final String PROVIDER_GLM4FLASH = "glm4flash";
    private static final String PROVIDER_DEEPSEEK = "deepseek";

    private final ChatClient zhipuChatClient;
    private final ChatClient glm4FlashChatClient;
    private final ChatClient deepSeekChatClient;
    private final ChatMemory chatMemory;
    private final GeneralAgentTools generalTools;
    private final KnowledgeAgentTool knowledgeTool;
    private final ObsidianProperties obsidianProperties;
    private final MessageLogRepository messageLogRepository;
    private final ConversationSettingService conversationSettingService;

    public WechatBridgeController(@Qualifier("zhipuChatModel") OpenAiChatModel zhipuChatModel,
                                  @Qualifier("glm4FlashChatModel") OpenAiChatModel glm4FlashChatModel,
                                  @Qualifier("deepSeekChatModel") OpenAiChatModel deepSeekChatModel,
                                  ChatMemory chatMemory,
                                  GeneralAgentTools generalTools, KnowledgeAgentTool knowledgeTool,
                                  ObsidianProperties obsidianProperties,
                                  MessageLogRepository messageLogRepository,
                                  ConversationSettingService conversationSettingService) {
        this.generalTools = generalTools;
        this.knowledgeTool = knowledgeTool;
        this.obsidianProperties = obsidianProperties;
        this.messageLogRepository = messageLogRepository;
        this.conversationSettingService = conversationSettingService;
        this.chatMemory = chatMemory;
        this.zhipuChatClient = ChatClient.builder(zhipuChatModel).defaultSystem(SYSTEM_PROMPT).build();
        this.glm4FlashChatClient = ChatClient.builder(glm4FlashChatModel).defaultSystem(SYSTEM_PROMPT).build();
        this.deepSeekChatClient = ChatClient.builder(deepSeekChatModel).defaultSystem(SYSTEM_PROMPT).build();
    }

    /** 健康检查：Python 端 / 前端启动时可先调用此接口确认 Java 服务在线 */
    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("ok");
    }

    /** 会话列表（仅 web 渠道），标题 = 每条会话第一条收到消息 */
    @GetMapping("/sessions")
    public List<SessionSummary> sessions() {
        return messageLogRepository.listSessions();
    }

    /** 某个会话的完整历史（按时间正序） */
    @GetMapping("/sessions/{conversationId}/messages")
    public List<ChatMessage> sessionMessages(@PathVariable String conversationId) {
        return messageLogRepository.listByConversation(conversationId);
    }

    /** 删除某个会话（流水 + 对话记忆 + 会话设置一起清） */
    @DeleteMapping("/sessions/{conversationId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String conversationId) {
        int deleted = messageLogRepository.deleteByConversation(conversationId);
        try {
            chatMemory.clear(conversationId);
        } catch (Exception e) {
            log.warn("清空会话记忆失败（不影响删除流水）：{}", e.getMessage());
        }
        try {
            conversationSettingService.delete(conversationId);
        } catch (Exception e) {
            log.warn("删除会话设置失败（不影响删除流水）：{}", e.getMessage());
        }
        log.info("删除会话 [{}]（流水 {} 条）", conversationId, deleted);
        return ResponseEntity.noContent().build();
    }

    /** 查看某会话的模型设置（无记录返回 404，前端按默认值展示） */
    @GetMapping("/conversations/{conversationId}/setting")
    public ResponseEntity<ConversationSettingDto> getConversationSetting(@PathVariable String conversationId) {
        ConversationSetting setting = conversationSettingService.get(conversationId);
        if (setting == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDto(setting));
    }

    /** 更新某会话的模型设置（允许部分字段；无记录则创建） */
    @PutMapping("/conversations/{conversationId}/setting")
    public ResponseEntity<?> updateConversationSetting(@PathVariable String conversationId,
                                                       @RequestBody(required = false) ConversationSettingUpdateRequest body) {
        String preferred = body == null ? null : body.preferredModel();
        if (preferred != null && !PROVIDER_ZHIPU.equals(preferred)
                && !PROVIDER_GLM4FLASH.equals(preferred) && !PROVIDER_DEEPSEEK.equals(preferred)) {
            return ResponseEntity.badRequest().body("preferredModel 只能是 zhipu / glm4flash / deepseek");
        }
        ConversationSetting setting = conversationSettingService.upsert(
                conversationId,
                body == null ? null : body.scene(),
                preferred,
                body == null ? null : body.allowDeepseek());
        return ResponseEntity.ok(toDto(setting));
    }

    /**
     * 核心接口：接收消息，由 Agent 自主调用工具后调用远端大模型，返回 AI 回复。
     *
     * @param request     JSON 请求体：{"sender": "...", "content": "...", "useRag": true, "scene": "friend/group/web", "chat": "群名", "sessionId": "...", "provider": "zhipu/deepseek"}
     * @param httpRequest 原始 HTTP 请求，用于取客户端 IP（web 无 sessionId 时的兜底会话键）
     * @return JSON 响应体：{"reply": "AI 回复内容", "ragUsed": "是否允许知识库", "provider": "实际使用的模型"}
     */
    @PostMapping("/reply")
    public ResponseEntity<ReplyResponse> reply(@RequestBody(required = false) ReplyRequest request,
                                               HttpServletRequest httpRequest) {
        if (request == null || request.content() == null || request.content().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(new ReplyResponse("消息内容为空，无法处理。", false, null));
        }

        String text = request.content().trim();
        String sender = request.sender() == null ? "" : request.sender().trim();
        String sessionId = request.sessionId() == null ? "" : request.sessionId().trim();
        String clientIp = resolveClientIp(httpRequest);

        // 渠道：scene 缺省时做兼容判断（web 前端曾用 sender=web）
        String sceneRaw = request.scene() == null ? "" : request.scene().trim().toLowerCase();
        String scene = switch (sceneRaw) {
            case "web" -> "web";
            case "group" -> "group";
            case "friend" -> "friend";
            default -> "web".equals(sender) ? "web" : "friend";
        };

        // 会话 ID：一个聊天对象 / 一个 web 会话 = 一个会话，按渠道拼键
        String conversationId;
        if ("web".equals(scene)) {
            conversationId = sessionId.isEmpty() ? "web:" + clientIp : "web:" + sessionId;
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
        safeLogMessage(conversationId, scene, "RECEIVED", sender, clientIp, text, "", ragEnabled);

        // 记忆控制命令：清空当前会话的上下文
        if (CLEAR_MEMORY_COMMAND.equals(text)) {
            chatMemory.clear(conversationId);
            String ack = "好的，已清空我们之前的聊天记忆，重新开始聊吧。";
            MessageTraceLogger.sent(sender, ack);
            safeLogMessage(conversationId, scene, "SENT", sender, clientIp, ack, "", ragEnabled);
            log.info("已清空会话 [{}] 的对话记忆", conversationId);
            return ResponseEntity.ok(new ReplyResponse(ack, false, null));
        }

        // 模型选择：请求显式 provider > 会话偏好 > 默认智谱；deepseek 需授权，未授权强制回落
        String provider = resolveProvider(request.provider(), conversationId);
        ChatClient chatClient = switch (provider) {
            case PROVIDER_DEEPSEEK -> deepSeekChatClient;
            case PROVIDER_GLM4FLASH -> glm4FlashChatClient;
            default -> zhipuChatClient;
        };

        // 组装本次可用的工具：通用工具始终可用，知识库工具按 RAG 开关动态追加
        List<Object> tools = new ArrayList<>();
        tools.add(generalTools);
        if (ragEnabled) {
            tools.add(knowledgeTool);
        }

        try {
            // 组装 Prompt：系统提示 + 该会话的历史记忆 + 当前消息（记忆两个模型共享）
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(SYSTEM_PROMPT));
            messages.addAll(chatMemory.get(conversationId));
            messages.add(new UserMessage(text));

            // Agent 化调用：模型在生成过程中自主决定是否调用已注册的工具，
            // Spring AI 自动完成「工具调用 → 结果回填 → 再作答」的循环。
            String providerUsed = provider;
            String reply;
            try {
                reply = chatClient.prompt()
                        .messages(messages)
                        .tools(tools.toArray())
                        .call()
                        .content();
            } catch (Exception modelException) {
                if (!PROVIDER_DEEPSEEK.equals(provider)) {
                    throw modelException;
                }
                // DeepSeek 付费通道失败（余额不足/网络等）→ 自动回落免费智谱，不断聊
                log.error("DeepSeek 调用失败（会话 {}），自动回落智谱", conversationId, modelException);
                providerUsed = PROVIDER_ZHIPU;
                reply = zhipuChatClient.prompt()
                        .messages(messages)
                        .tools(tools.toArray())
                        .call()
                        .content();
            }

            if (reply == null || reply.isBlank()) {
                reply = "抱歉，AI 没有返回内容，请再试一次。";
            }
            // 写回记忆：用户消息 + AI 回复（MessageWindowChatMemory 会自动裁剪窗口）
            chatMemory.add(conversationId,
                    List.of(new UserMessage(text), new AssistantMessage(reply)));
            MessageTraceLogger.sent(sender, reply);
            safeLogMessage(conversationId, scene, "SENT", sender, clientIp, reply, providerUsed, ragEnabled);
            log.info("收到 [{}] 的消息（{} 字），[{}] 回复（{} 字）",
                    sender, text.length(), providerUsed, reply.trim().length());
            return ResponseEntity.ok(new ReplyResponse(reply.trim(), ragEnabled, providerUsed));
        } catch (Exception e) {
            // AI 接口异常时不要静默丢消息：返回 200 + 友好兜底文案，Python 端会把提示发回微信
            log.error("调用远端大模型失败，会话 [{}]，原始消息：{}", conversationId, text, e);
            String fallback = "抱歉，AI 服务暂时不可用，请稍后再试。";
            MessageTraceLogger.sent(sender, fallback);
            safeLogMessage(conversationId, scene, "SENT", sender, clientIp, fallback, "", ragEnabled);
            return ResponseEntity.ok(new ReplyResponse(fallback, ragEnabled, null));
        }
    }

    /**
     * 决定本次实际使用的模型：
     * 请求显式 provider 优先，其次会话偏好，最后智谱；deepseek 未授权一律回落智谱。
     */
    private String resolveProvider(String requested, String conversationId) {
        ConversationSetting setting = conversationSettingService.get(conversationId);
        String preferred = normalizeProvider(setting == null ? null : setting.getPreferredModel());
        boolean allowDeepseek = setting != null && Boolean.TRUE.equals(setting.getAllowDeepseek());
        String want = (requested == null || requested.isBlank())
                ? preferred
                : normalizeProvider(requested.trim());
        if (PROVIDER_DEEPSEEK.equals(want) && !allowDeepseek) {
            log.warn("会话 [{}] 未授权使用 deepseek，已强制回落 zhipu", conversationId);
            return PROVIDER_ZHIPU;
        }
        return want;
    }

    /** provider 归一化：glm-4-flash 别名归为 glm4flash，未知值归默认 zhipu */
    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return PROVIDER_ZHIPU;
        }
        return switch (provider.trim().toLowerCase()) {
            case PROVIDER_DEEPSEEK -> PROVIDER_DEEPSEEK;
            case PROVIDER_GLM4FLASH, "glm-4-flash" -> PROVIDER_GLM4FLASH;
            default -> PROVIDER_ZHIPU;
        };
    }

    /** 消息流水落库，失败只告警、不影响正常回复 */
    private void safeLogMessage(String conversationId, String scene, String direction,
                                String sender, String clientIp, String content,
                                String provider, boolean ragEnabled) {
        try {
            messageLogRepository.insert(conversationId, scene, direction, sender, clientIp,
                    "text", content, provider, ragEnabled);
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

    private static ConversationSettingDto toDto(ConversationSetting setting) {
        return new ConversationSettingDto(
                setting.getConversationId(),
                setting.getScene(),
                setting.getPreferredModel() == null ? PROVIDER_ZHIPU : setting.getPreferredModel(),
                Boolean.TRUE.equals(setting.getAllowDeepseek()));
    }

    /** 请求体 */
    public record ReplyRequest(String sender, String content, Boolean useRag,
                               String scene, String chat, String sessionId, String provider) {
    }

    /** 响应体：provider 为 null 表示本条不是模型生成（如清空记忆/系统兜底） */
    public record ReplyResponse(String reply, boolean ragUsed, String provider) {
    }

    /** 会话模型设置响应 */
    public record ConversationSettingDto(String conversationId, String scene,
                                         String preferredModel, boolean allowDeepseek) {
    }

    /** 会话模型设置更新请求 */
    public record ConversationSettingUpdateRequest(String scene, String preferredModel, Boolean allowDeepseek) {
    }

    /** 健康检查响应 */
    public record HealthResponse(String status) {
    }
}
