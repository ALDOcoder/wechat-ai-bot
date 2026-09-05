package com.example.wechataibot.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;

/**
 * 双模型配置：智谱（免费，默认）+ DeepSeek 官方（付费，需授权）。
 *
 * <p>⚠️ Spring AI 的 {@code OpenAiChatAutoConfiguration} 已在 application.yml 中排除，
 * 这里的两个 {@link OpenAiChatModel} 是全权来源：
 * <ul>
 *     <li>{@code zhipuChatModel}（@Primary）：GLM-4.7-Flash，永久免费，已验证 function calling；
 *         智谱 OpenAI 兼容路径是 {@code /api/paas/v4/chat/completions}，必须覆盖 completions-path
 *         （Spring AI 默认会拼 {@code /v1/chat/completions}，会导致 404）；</li>
 *     <li>{@code deepSeekChatModel}：deepseek-chat（官方 API，预付费），仅白名单会话可用，
 *         权限判断在 WechatBridgeController（conversation_setting.allow_deepseek）。</li>
 * </ul>
 *
 * <p>两个 key 都走环境变量：{@code ZHIPU_API_KEY} / {@code DEEPSEEK_API_KEY}，
 * 未配置时用占位符（调用会 401，提醒还没配置）。
 */
@Configuration
public class ModelConfig {

    /** 智谱免费模型 ID */
    private static final String ZHIPU_MODEL = "glm-4.7-flash";
    /** DeepSeek 官方对话模型 ID */
    private static final String DEEPSEEK_MODEL = "deepseek-chat";

    @Bean
    @Primary
    public OpenAiChatModel zhipuChatModel(
            @Value("${ZHIPU_API_KEY:sk-REPLACE_ME}") String zhipuApiKey,
            RetryTemplate retryTemplate) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl("https://open.bigmodel.cn/api/paas/v4")
                .apiKey(zhipuApiKey)
                .completionsPath("/chat/completions")
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(ZHIPU_MODEL)
                        .temperature(0.7)
                        .build())
                .retryTemplate(retryTemplate)
                .build();
    }

    @Bean
    public OpenAiChatModel deepSeekChatModel(
            @Value("${DEEPSEEK_API_KEY:sk-REPLACE_ME}") String deepSeekApiKey,
            RetryTemplate retryTemplate) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(deepSeekApiKey)
                .completionsPath("/v1/chat/completions")
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(DEEPSEEK_MODEL)
                        .temperature(0.7)
                        .build())
                .retryTemplate(retryTemplate)
                .build();
    }
}
