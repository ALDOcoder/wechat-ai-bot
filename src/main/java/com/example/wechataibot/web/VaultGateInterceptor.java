package com.example.wechataibot.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 知识库管理面门禁拦截器：/api/vault/** 与 /api/rag/** 一律要求有效 X-Vault-Token，
 * 否则 401（纯文本「需要密钥」）。RAG_VAULT_KEY 未配置时门禁关闭（直接放行）。
 *
 * <p>豁免路径（注册时排除，不走本拦截器）：
 * /api/vault/note/confirm、/api/vault/note/reject —— AI 写入确认不打断聊天流（拍板项），
 * 且草稿路径写死只能落在 agent 输出目录内。
 */
@Component
public class VaultGateInterceptor implements HandlerInterceptor {

    private final VaultTokenService tokenService;

    public VaultGateInterceptor(VaultTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        if (!tokenService.gateEnabled()) {
            return true;
        }
        String token = request.getHeader("X-Vault-Token");
        if (tokenService.validate(token)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("text/plain;charset=UTF-8");
        response.getOutputStream().write("需要密钥".getBytes(StandardCharsets.UTF_8));
        return false;
    }
}
