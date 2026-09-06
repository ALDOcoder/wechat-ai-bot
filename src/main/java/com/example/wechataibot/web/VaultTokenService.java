package com.example.wechataibot.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 知识库管理面门禁：环境变量密钥（RAG_VAULT_KEY）换临时令牌，令牌放内存、重启全失效。
 *
 * <p>安全设计：
 * <ul>
 *     <li>密钥常量时间比较（防时序侧信道）；只在换令牌接口出现一次，不随业务请求重复传输；</li>
 *     <li>令牌随机 256bit，30 分钟<b>滑动续期</b>（每次成功的管理请求刷新过期时间）；</li>
 *     <li>防爆破：按 IP 连错 5 次 → 锁 10 分钟；外加全局兜底（5 分钟内全服失败 ≥50 次 → 全局锁 10 分钟）；
 *         失败计数仅存内存，重启清零；</li>
 *     <li>防 XFF 伪造：仅当请求来自本机可信代理（remoteAddr 为回环地址）时才采信
 *         X-Forwarded-For（Vite 代理已开启 xfwd），直连 8080 一律用 remoteAddr，
 *         攻击者无法靠伪造头换 IP 绕开锁定；</li>
 *     <li><b>RAG_VAULT_KEY 未配置时管理面不上锁</b>（保持无门禁的现状），启动打 WARN 提示——
 *         前端可据 /api/rag/status 是否 401 决定是否显示锁屏。</li>
 * </ul>
 */
@Service
public class VaultTokenService {

    private static final Logger log = LoggerFactory.getLogger(VaultTokenService.class);

    /** 令牌有效期（滑动续期） */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    /** 单 IP 连错次数上限 */
    private static final int MAX_FAILS_PER_IP = 5;
    /** 单 IP 锁定时长 */
    private static final Duration IP_LOCK = Duration.ofMinutes(10);
    /** 全局兜底：时间窗口内的失败总数上限 */
    private static final int GLOBAL_MAX_FAILS = 50;
    /** 全局失败计数窗口 */
    private static final Duration GLOBAL_WINDOW = Duration.ofMinutes(5);
    /** 全局锁定时长 */
    private static final Duration GLOBAL_LOCK = Duration.ofMinutes(10);

    private final String vaultKey;
    private final ConcurrentHashMap<String, Instant> tokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FailState> failsByIp = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final AtomicInteger globalFails = new AtomicInteger();
    private volatile Instant globalWindowStart = Instant.EPOCH;
    private volatile Instant globalLockUntil = Instant.EPOCH;

    public VaultTokenService(@Value("${RAG_VAULT_KEY:}") String vaultKey) {
        this.vaultKey = vaultKey == null ? "" : vaultKey.trim();
        if (this.vaultKey.isBlank()) {
            log.warn("RAG_VAULT_KEY 未配置：知识库管理面（/api/vault/**、/api/rag/**）当前【未上锁】，"
                    + "配置环境变量后重启即自动启用门禁");
        }
    }

    /** 门禁是否启用（密钥已配置） */
    public boolean gateEnabled() {
        return !vaultKey.isBlank();
    }

    /**
     * 校验密钥并签发令牌。
     *
     * @throws VaultAuthException 401 密钥不正确 / 429 尝试过多（{@link #isRateLimited()} 区分）
     */
    public IssuedToken issue(String key, HttpServletRequest request) {
        if (!gateEnabled()) {
            throw new VaultAuthException(400, "未配置 RAG_VAULT_KEY，管理面未上锁，无需换令牌");
        }
        String ip = clientKey(request);
        FailState state = failsByIp.computeIfAbsent(ip, k -> new FailState());
        Instant now = Instant.now();
        if (now.isBefore(state.lockUntil)) {
            throw new VaultAuthException(429, "尝试次数过多，请 10 分钟后再试");
        }
        if (now.isBefore(globalLockUntil)) {
            throw new VaultAuthException(429, "尝试次数过多，请稍后再试");
        }

        if (matchesKey(key)) {
            state.failCount = 0;
            globalFails.set(0);
            String token = newToken();
            tokens.put(token, now.plus(TOKEN_TTL));
            return new IssuedToken(token, TOKEN_TTL.toSeconds());
        }

        // 记失败：单 IP 计数 + 全局窗口计数
        state.failCount++;
        if (state.failCount >= MAX_FAILS_PER_IP) {
            state.lockUntil = now.plus(IP_LOCK);
            state.failCount = 0;
            log.warn("管理面密钥连错 {} 次，IP [{}] 已锁定 10 分钟", MAX_FAILS_PER_IP, ip);
        }
        if (now.isAfter(globalWindowStart.plus(GLOBAL_WINDOW))) {
            globalWindowStart = now;
            globalFails.set(0);
        }
        if (globalFails.incrementAndGet() >= GLOBAL_MAX_FAILS) {
            globalLockUntil = now.plus(GLOBAL_LOCK);
            log.warn("管理面密钥 {} 分钟内失败 {} 次，全局锁定 10 分钟", GLOBAL_WINDOW.toMinutes(), GLOBAL_MAX_FAILS);
        }
        throw new VaultAuthException(401, "密钥不正确");
    }

    /** 校验令牌并滑动续期 */
    public boolean validate(String token) {
        if (!gateEnabled() || token == null || token.isBlank()) {
            return false;
        }
        Instant expiry = tokens.get(token);
        if (expiry == null || Instant.now().isAfter(expiry)) {
            tokens.remove(token);
            return false;
        }
        tokens.put(token, Instant.now().plus(TOKEN_TTL));
        return true;
    }

    /** 主动失效（前端「退出管理」按钮） */
    public void close(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }

    /** 全局是否处于锁定窗口（issue 时也会查，此方法供日志/调试） */
    public boolean isRateLimited(HttpServletRequest request) {
        Instant now = Instant.now();
        if (now.isBefore(globalLockUntil)) {
            return true;
        }
        FailState state = failsByIp.get(clientKey(request));
        return state != null && now.isBefore(state.lockUntil);
    }

    private boolean matchesKey(String supplied) {
        if (supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(
                vaultKey.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 防伪造的客户端标识：仅当 remoteAddr 是回环地址（本机 Vite 代理）时才采信
     * X-Forwarded-For；直连请求一律用 remoteAddr，客户端自带的 XFF 被忽略。
     */
    private String clientKey(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        boolean trustedProxy = "127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote);
        if (trustedProxy) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            }
        }
        return remote;
    }

    /** 换令牌成功结果 */
    public record IssuedToken(String token, long remainingSeconds) {
    }

    /** 门禁异常：status 为 401 / 429 / 400，message 为可直接展示的纯文本 */
    public static class VaultAuthException extends RuntimeException {
        private final int status;

        public VaultAuthException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    /** 单 IP 失败状态 */
    private static class FailState {
        private int failCount;
        private volatile Instant lockUntil = Instant.EPOCH;
    }
}
