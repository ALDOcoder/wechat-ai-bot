package com.example.wechataibot.web;

import com.example.wechataibot.config.ObsidianProperties;
import com.example.wechataibot.persistence.RagProtectService;
import com.example.wechataibot.rag.VaultIndexService;
import com.example.wechataibot.rag.VaultIndexService.Classification;
import com.example.wechataibot.rag.VaultIndexService.RuleSet;
import com.example.wechataibot.rag.VaultIndexService.Status;
import com.example.wechataibot.rag.VaultNoteService;
import com.example.wechataibot.rag.VaultNoteService.WriteResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 知识库管理面接口（除 AI 写入确认外，全部要求 X-Vault-Token，见 {@link VaultGateInterceptor}）：
 *
 * <ul>
 *     <li>POST /api/vault/session        —— 密钥换临时令牌（防爆破：单 IP 连错 5 次锁 10 分钟）；</li>
 *     <li>POST /api/vault/session/close  —— 主动失效令牌；</li>
 *     <li>GET  /api/vault/tree           —— 库内 md 文件树（indexed/excluded/protected 三态，
 *         安全基线路径任何接口不可见，未加入索引的保护目录只返回折叠节点不露子文件名）；</li>
 *     <li>POST /api/vault/protect/add|remove —— 受保护目录加入/移出索引（持久化，可逆）；</li>
 *     <li>POST /api/vault/note           —— 手动新建笔记（合法路径、同名 409）；</li>
 *     <li>POST /api/vault/note/confirm|reject —— AI 草稿确认/拒绝（豁免令牌，不打断聊天流）。</li>
 * </ul>
 *
 * <p>⚠️ 密钥 RAG_VAULT_KEY 走环境变量，不要写进提交的配置文件；接口仅限本机/局域网使用。
 */
@RestController
@RequestMapping("/api/vault")
public class VaultController {

    private final VaultTokenService tokenService;
    private final ObsidianProperties props;
    private final VaultIndexService indexService;
    private final RagProtectService protectService;
    private final VaultNoteService noteService;

    public VaultController(VaultTokenService tokenService, ObsidianProperties props,
                           VaultIndexService indexService, RagProtectService protectService,
                           VaultNoteService noteService) {
        this.tokenService = tokenService;
        this.props = props;
        this.indexService = indexService;
        this.protectService = protectService;
        this.noteService = noteService;
    }

    /** 密钥换令牌：成功 {token, remainingSeconds}；401 密钥不正确；429 尝试过多 */
    @PostMapping("/session")
    public ResponseEntity<?> session(@RequestBody(required = false) SessionRequest body,
                                     jakarta.servlet.http.HttpServletRequest request) {
        try {
            VaultTokenService.IssuedToken issued = tokenService.issue(
                    body == null ? null : body.key(), request);
            return ResponseEntity.ok(issued);
        } catch (VaultTokenService.VaultAuthException e) {
            return ResponseEntity.status(e.getStatus()).body(e.getMessage());
        }
    }

    /** 主动失效令牌（前端「退出管理」按钮） */
    @PostMapping("/session/close")
    public Map<String, Object> closeSession(@RequestBody(required = false) TokenRequest body) {
        tokenService.close(body == null ? null : body.token());
        return Map.of("closed", true);
    }

    /** 库内 md 文件树（扁平路径列表，前端自行组树）：三态 + 保护目录折叠节点 */
    @GetMapping("/tree")
    public Map<String, Object> tree() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (!props.isEnabled()) {
            return Map.of("root", props.getVaultPath() == null ? "" : props.getVaultPath(), "nodes", nodes);
        }
        Path root = Paths.get(props.getVaultPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return Map.of("root", props.getVaultPath(), "nodes", nodes);
        }

        RuleSet rules = indexService.currentRules();
        // 保护目录 → 子文件计数（未加入索引的目录只返回折叠节点）；
        // 键用小写（classify 返回的 dir 是小写），值含展示名与计数
        Map<String, Object[]> protectedCounts = new LinkedHashMap<>();
        for (String dir : rules.protectedDirs()) {
            if (dir != null && !dir.isBlank() && !isJoined(dir, rules)) {
                String key = normalizeDir(dir).toLowerCase();
                protectedCounts.putIfAbsent(key, new Object[]{normalizeDir(dir), 0});
            }
        }

        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".md"))
                    .forEach(p -> {
                        String displayRel = root.relativize(p).toString().replace('\\', '/');
                        Classification cls = VaultIndexService.classify(
                                VaultIndexService.normalizeRel(p, root), rules);
                        switch (cls.status()) {
                            case BASELINE -> { /* 安全基线：任何接口不可见 */ }
                            case PROTECTED -> {
                                Object[] slot = protectedCounts.get(cls.dir());
                                if (slot != null) {
                                    slot[1] = (Integer) slot[1] + 1;
                                }
                            }
                            case EXCLUDED -> nodes.add(Map.of(
                                    "path", displayRel, "status", "excluded", "rule", cls.rule() == null ? "" : cls.rule()));
                            case INDEXED -> nodes.add(Map.of("path", displayRel, "status", "indexed"));
                        }
                    });
        } catch (Exception e) {
            return Map.of("root", props.getVaultPath(), "nodes", nodes, "lastError",
                    "扫描 vault 失败: " + e.getMessage());
        }
        // 折叠节点放在列表最前，path 以 / 结尾
        for (Object[] slot : protectedCounts.values()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("path", slot[0] + "/");
            node.put("status", "protected");
            node.put("fileCount", slot[1]);
            nodes.add(0, node);
        }
        return Map.of("root", props.getVaultPath(), "nodes", nodes);
    }

    /** 受保护目录加入索引（持久化，重启不丢） */
    @PostMapping("/protect/add")
    public ResponseEntity<?> protectAdd(@RequestBody(required = false) PathRequest body) {
        return protectSwitch(body, true);
    }

    /** 受保护目录移出索引（幂等） */
    @PostMapping("/protect/remove")
    public ResponseEntity<?> protectRemove(@RequestBody(required = false) PathRequest body) {
        return protectSwitch(body, false);
    }

    private ResponseEntity<?> protectSwitch(PathRequest body, boolean join) {
        String dir = body == null ? null : body.path();
        if (dir == null || !isConfiguredProtected(dir)) {
            return ResponseEntity.badRequest().body("不是受保护的目录");
        }
        String normalized = normalizeDir(dir);
        if (join) {
            protectService.join(normalized);
        } else {
            protectService.leave(normalized);
        }
        int chunks = indexService.rebuild();
        return ResponseEntity.ok(Map.of(
                "indexed", join,
                "files", indexService.getFileCount(),
                "chunks", chunks,
                "lastError", indexService.getLastError()));
    }

    /** 手动新建笔记（落盘 + 进索引；字典排除目录允许写入但不进索引） */
    @PostMapping("/note")
    public ResponseEntity<?> createNote(@RequestBody(required = false) NoteRequest body) {
        try {
            WriteResult result = noteService.createNote(
                    body == null ? null : body.path(), body == null ? null : body.content());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (java.nio.file.FileAlreadyExistsException e) {
            return ResponseEntity.status(409).body("文件已存在：" + e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /** 确认 AI 草稿写入（豁免令牌）：404 = 草稿不存在或已过期 */
    @PostMapping("/note/confirm")
    public ResponseEntity<?> confirmNote(@RequestBody(required = false) PendingRequest body) {
        WriteResult result = noteService.confirmWrite(body == null ? null : body.pendingId());
        if (result == null) {
            return ResponseEntity.status(404).body("确认已过期，请让 AI 重新生成");
        }
        return ResponseEntity.ok(result);
    }

    /** 拒绝 AI 草稿（幂等，豁免令牌） */
    @PostMapping("/note/reject")
    public Map<String, Object> rejectNote(@RequestBody(required = false) PendingRequest body) {
        return Map.of("rejected", noteService.reject(body == null ? null : body.pendingId()));
    }

    // ==================== 内部工具 ====================

    private boolean isConfiguredProtected(String dir) {
        for (String candidate : props.getProtectedDirs()) {
            if (candidate != null && candidate.trim().equalsIgnoreCase(normalizeDir(dir))) {
                return true;
            }
        }
        return false;
    }

    private boolean isJoined(String dir, RuleSet rules) {
        String normalized = normalizeDir(dir);
        return rules.joinedProtected().stream().anyMatch(j -> normalizeDir(j).equals(normalized));
    }

    /** 目录名归一：正斜杠、去首尾空白与结尾斜杠 */
    private static String normalizeDir(String dir) {
        String d = dir.trim().replace('\\', '/');
        while (d.endsWith("/")) {
            d = d.substring(0, d.length() - 1);
        }
        return d;
    }

    /** 密钥换令牌请求体 */
    public record SessionRequest(String key) {
    }

    /** 主动失效请求体 */
    public record TokenRequest(String token) {
    }

    /** 保护目录操作请求体 */
    public record PathRequest(String path) {
    }

    /** 手动新建笔记请求体 */
    public record NoteRequest(String path, String content) {
    }

    /** AI 草稿确认/拒绝请求体 */
    public record PendingRequest(String pendingId) {
    }
}
