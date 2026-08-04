package com.example.wechataibot.bot;

import com.example.wechataibot.config.WeChatBotProperties;
import io.uouo.wechat.WeChatBot;
import io.uouo.wechat.api.annotation.Bind;
import io.uouo.wechat.api.constant.Config;
import io.uouo.wechat.api.enums.AccountType;
import io.uouo.wechat.api.enums.MsgType;
import io.uouo.wechat.api.model.WeChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 核心消息处理类：继承 {@link WeChatBot}，收到文本消息后调用远端大模型，
 * 并把 AI 的回复发给发送者。
 *
 * <p>⚠️ 风险提示：
 * <ul>
 *     <li>个人微信自动化违反微信用户协议，有封号风险，务必使用【测试小号】；</li>
 *     <li>本项目使用 wechat-api 的 UOS 协议 fork（wechat-api-uouo）解决原版登录后被踢下线的问题；
 *         若账号仍无法登录网页版，说明微信已对该账号关闭网页版通道，需更换其他方案；</li>
 *     <li>聊天内容会发送到第三方大模型服务，注意隐私安全；</li>
 *     <li>框架为单线程顺序处理消息，AI 调用耗时期间后续消息会排队，
 *         若消息量大建议改用线程池异步处理并控制发送频率，避免触发微信风控。</li>
 * </ul>
 */
@Component
public class AiWeChatBot extends WeChatBot {

    private static final Logger log = LoggerFactory.getLogger(AiWeChatBot.class);

    /** 给大模型的系统提示词，可自行调整人设 */
    private static final SystemMessage SYSTEM_MESSAGE =
            new SystemMessage("你是一个友善、简洁的微信聊天助手，请用自然的中文与用户聊天，回答尽量简短。");

    private final ChatModel chatModel;
    private final WeChatBotProperties properties;

    /**
     * @param chatModel Spring AI 自动注入的 OpenAI 兼容 ChatModel
     *                  （由 spring.ai.openai.base-url / api-key 配置驱动，远端 HTTPS 调用）
     * @param properties 微信机器人配置（application.yml 中 wechat.bot.*）
     */
    public AiWeChatBot(ChatModel chatModel, WeChatBotProperties properties) {
        super(Config.me()
                .autoLogin(properties.isAutoLogin())
                .showTerminal(properties.isShowTerminal())
                .autoReply(properties.isAutoReply()));
        this.chatModel = chatModel;
        this.properties = properties;
    }

    /**
     * 是否启用旧网页版方案（application.yml 的 wechat.bot.enabled）。
     * 默认 false，由 Python + wxauto 负责微信收发。
     */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 注解驱动：绑定“好友私聊文本消息”处理。
     * 收到文本后调用远端大模型，并将结果回复给发送者。
     *
     * @param message 微信消息封装，包含发送者、文本内容等
     */
    @Bind(msgType = MsgType.TEXT, accountType = AccountType.TYPE_FRIEND)
    public void handleText(WeChatMessage message) {
        String fromUserName = message.getFromUserName();
        String text = message.getText();

        if (text == null || text.trim().isEmpty()) {
            return;
        }
        // 显式过滤群消息：联系人列表加载不全时，框架的 accountType 过滤可能失效，
        // 加上 isGroup 判断避免在群里自动回复打扰他人（也降低风控风险）。
        if (message.isGroup()) {
            return;
        }

        log.info("收到来自 [{}] 的文本消息：{}", message.getName(), text);
        try {
            // 调用远端大模型（DeepSeek / OpenAI 等，通过 spring.ai.openai.* 配置）
            String reply = chatModel.call(new Prompt(List.of(SYSTEM_MESSAGE, new UserMessage(text))))
                    .getResult()
                    .getOutput()
                    .getText();

            log.info("AI 回复 [{}]：{}", message.getName(), reply);
            // 把 AI 结果回复给发送者
            sendMsg(fromUserName, reply);
        } catch (Exception e) {
            // AI 接口异常时不要静默，给发送者一个友好提示并记录日志
            log.error("调用远端大模型失败，原始消息：{}", text, e);
            sendMsg(fromUserName, "抱歉，我暂时开小差了，请稍后再试～");
        }
    }
}
