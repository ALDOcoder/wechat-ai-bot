package com.example.wechataibot.config;

import com.example.wechataibot.web.VaultGateInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册知识库管理面门禁拦截器。
 *
 * <p>门禁范围：/api/vault/** 与 /api/rag/**（文件树、规则、检索调试、索引状态全部上锁）。
 * 聊天面（/api/reply、/api/health、/api/sessions*、/api/conversations*）不受影响。
 * 豁免路径：
 * <ul>
 *     <li>/api/vault/session —— 密钥换令牌的入口（否则自己把自己锁死）；</li>
 *     <li>/api/vault/note/confirm、/api/vault/note/reject —— AI 写入确认不打断聊天流（拍板项）。</li>
 * </ul>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final VaultGateInterceptor vaultGateInterceptor;

    public WebMvcConfig(VaultGateInterceptor vaultGateInterceptor) {
        this.vaultGateInterceptor = vaultGateInterceptor;
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(vaultGateInterceptor)
                .addPathPatterns("/api/vault/**", "/api/rag/**")
                .excludePathPatterns("/api/vault/session",
                        "/api/vault/note/confirm", "/api/vault/note/reject");
    }
}
