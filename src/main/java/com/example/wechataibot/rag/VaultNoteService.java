package com.example.wechataibot.rag;

import com.example.wechataibot.config.ObsidianProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 笔记写入服务：统一承载「AI 两阶段写入」与「手动新建笔记」的落盘逻辑。
 *
 * <p>AI 写入走两阶段（防提示注入直接落盘）：Agent 调 {@code draft} 只生成草稿条目
 * （内存暂存 10 分钟），随回复返回给前端确认；用户点确认后才 {@code confirmWrite} 落盘
 * 并重建索引。手动新建（/api/vault/note）直接落盘，但路径受同样校验约束。
 *
 * <p>安全护栏（两类写入共用）：
 * <ul>
 *     <li>AI 草稿只落在 {@code obsidian.agent-output-dir} 目录内；手动新建允许库内任意
 *         合法路径，但安全基线与未加入索引的保护目录一律拒绝；</li>
 *     <li>文件名清洗（去路径分隔符/非法字符/限长）+ resolve 后越界校验，防路径穿越；</li>
 *     <li>永不覆盖：目标已存在时自动加 -2/-3 序号（手动新建遇同名直接 409）；</li>
 *     <li>单篇正文限长 2 万字符。</li>
 * </ul>
 */
@Service
public class VaultNoteService {

    private static final Logger log = LoggerFactory.getLogger(VaultNoteService.class);

    /** 文件名（标题）最大长度 */
    private static final int MAX_TITLE_CHARS = 80;
    /** 单篇正文最大字符数 */
    private static final int MAX_CONTENT_CHARS = 20_000;
    /** 草稿有效期 */
    private static final long DRAFT_TTL_SECONDS = 600;
    /** 文件名非法字符：路径分隔符 + Windows 保留符号 + 控制字符 */
    private static final Pattern ILLEGAL_FILENAME = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    private final ObsidianProperties props;
    private final VaultIndexService indexService;
    private final ConcurrentHashMap<String, PendingNote> pendings = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** 本次请求线程中生成的草稿（非流式调用时工具与 Controller 同线程），回复后由 Controller 取走 */
    private final ThreadLocal<List<PendingNote>> currentDrafts = ThreadLocal.withInitial(ArrayList::new);

    public VaultNoteService(ObsidianProperties props, VaultIndexService indexService) {
        this.props = props;
        this.indexService = indexService;
    }

    /** 一条待确认的笔记草稿 */
    public record PendingNote(String id, String path, String content, Instant expiresAt) {
    }

    /** 落盘结果：实际路径（可能带 -N 序号）+ 重建后的索引数 */
    public record WriteResult(String path, int files, int chunks) {
    }

    // ==================== AI 两阶段写入 ====================

    /**
     * 生成笔记草稿（不落盘）。写入未启用时抛 {@link IllegalStateException}，
     * 标题/内容非法时抛 {@link IllegalArgumentException}。
     */
    public PendingNote draft(String title, String content) {
        Path root = requireWritableRoot();
        Path dir = root.resolve(props.getAgentOutputDir()).normalize();
        if (!dir.startsWith(root) || dir.equals(root)) {
            throw new IllegalStateException("agent-output-dir 配置非法（必须在库根目录之下）");
        }

        purgeExpired();
        String cleanTitle = sanitizeTitle(title);
        String fileName = uniqueFileName(dir, cleanTitle);
        String rel = props.getAgentOutputDir().replace('\\', '/')
                + (props.getAgentOutputDir().endsWith("/") ? "" : "/") + fileName;

        String body = capContent(content);
        String heading = cleanTitle.isBlank() ? "Agent 笔记" : cleanTitle;
        PendingNote note = new PendingNote(newDraftId(), rel, "# " + heading + "\n\n" + body + "\n",
                Instant.now().plusSeconds(DRAFT_TTL_SECONDS));
        pendings.put(note.id(), note);
        currentDrafts.get().add(note);
        log.info("Agent 生成笔记草稿：{}（pendingId={}，待用户确认）", rel, note.id());
        return note;
    }

    /** 取走本次请求线程中生成的全部草稿（Controller 组装响应后调用） */
    public List<PendingNote> drainDrafts() {
        List<PendingNote> drafts = List.copyOf(currentDrafts.get());
        currentDrafts.get().clear();
        return drafts;
    }

    /**
     * 确认写入：草稿落盘 + 重建索引。草稿不存在/已过期返回 null（前端提示重新生成）。
     * 确认时若目标路径刚被占用，自动加 -N 序号，以返回的实际路径为准。
     */
    public WriteResult confirmWrite(String pendingId) {
        PendingNote note = pendings.remove(pendingId);
        if (note == null || Instant.now().isAfter(note.expiresAt())) {
            return null;
        }
        Path root = requireWritableRoot();
        Path relTarget = Paths.get(note.path());
        Path dir = root.resolve(relTarget.getParent()).normalize();
        String base = relTarget.getFileName().toString().replaceAll("(?i)\\.md$", "");
        Path target = uniqueTarget(dir, base);
        if (!target.normalize().startsWith(root)) {
            throw new IllegalArgumentException("路径不合法：越出库根目录");
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(target, note.content(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("确认写入笔记失败：{}", note.path(), e);
            throw new IllegalStateException("写入文件失败：" + e.getMessage());
        }
        int chunks = indexService.rebuild();
        String rel = root.relativize(target).toString().replace('\\', '/');
        log.info("确认写入笔记：{}（索引重建后 {} 块）", rel, chunks);
        return new WriteResult(rel, indexService.getFileCount(), chunks);
    }

    /** 拒绝草稿（幂等） */
    public boolean reject(String pendingId) {
        return pendingId != null && pendings.remove(pendingId) != null;
    }

    // ==================== 手动新建笔记 ====================

    /**
     * 在库内任意合法路径新建笔记（无则建父目录，同名 409）。
     * 安全基线与未加入索引的保护目录内拒绝写入；字典排除目录允许写入但不进索引。
     *
     * @throws IllegalArgumentException 路径非法（空/非 .md/越界/基线/未加入索引的保护目录）
     * @throws FileAlreadyExistsException 文件已存在
     */
    public WriteResult createNote(String relPath, String content) throws FileAlreadyExistsException {
        Path root = requireVaultRoot();
        String p = relPath == null ? "" : relPath.trim().replace('\\', '/');
        if (p.isEmpty() || p.endsWith("/")) {
            throw new IllegalArgumentException("路径不能为空");
        }
        if (!p.toLowerCase().endsWith(".md")) {
            throw new IllegalArgumentException("路径不合法：仅支持 .md 文件");
        }
        Path target = root.resolve(p).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("路径不合法：越出库根目录");
        }
        VaultIndexService.Classification cls = VaultIndexService.classify(
                VaultIndexService.normalizeRel(target, root), indexService.currentRules());
        if (cls.status() == VaultIndexService.Status.BASELINE) {
            throw new IllegalArgumentException("路径不合法：安全基线路径不可写入");
        }
        if (cls.status() == VaultIndexService.Status.PROTECTED) {
            throw new IllegalArgumentException("路径不合法：落在未加入索引的保护目录内（先在文件树中解锁该目录）");
        }
        if (Files.exists(target)) {
            throw new FileAlreadyExistsException(root.relativize(target).toString().replace('\\', '/'));
        }
        try {
            Files.createDirectories(target.getParent());
            String body = content == null ? "" : content.trim();
            if (body.length() > MAX_CONTENT_CHARS) {
                throw new IllegalArgumentException("正文超过 " + MAX_CONTENT_CHARS + " 字符");
            }
            Files.writeString(target, body + "\n", StandardCharsets.UTF_8);
        } catch (FileAlreadyExistsException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("写入文件失败：" + e.getMessage());
        }
        int chunks = indexService.rebuild();
        String rel = root.relativize(target).toString().replace('\\', '/');
        log.info("手动新建笔记：{}（索引重建后 {} 块）", rel, chunks);
        return new WriteResult(rel, indexService.getFileCount(), chunks);
    }

    // ==================== 内部工具 ====================

    /** 笔记写入前提：vault 已启用且 agent 输出目录已配置 */
    private Path requireWritableRoot() {
        if (!props.isEnabled()) {
            throw new IllegalStateException("Obsidian 库未启用");
        }
        if (props.getAgentOutputDir().isBlank()) {
            throw new IllegalStateException("未配置 obsidian.agent-output-dir");
        }
        return requireVaultRoot();
    }

    private Path requireVaultRoot() {
        if (!props.isEnabled()) {
            throw new IllegalArgumentException("Obsidian 库未启用");
        }
        Path root = Paths.get(props.getVaultPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("vault 路径不存在");
        }
        return root;
    }

    /** 文件名清洗：去非法字符、折叠空白、限长；空标题用时间戳兜底；去结尾点/空格 */
    private static String sanitizeTitle(String title) {
        String t = title == null ? "" : title.trim();
        t = ILLEGAL_FILENAME.matcher(t).replaceAll(" ");
        t = t.replaceAll("\\s+", " ").trim();
        if (t.length() > MAX_TITLE_CHARS) {
            t = t.substring(0, MAX_TITLE_CHARS).trim();
        }
        if (t.isBlank()) {
            t = "笔记-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        }
        return t.replaceAll("[. ]+$", "");
    }

    /** 同名自动加 -2/-3 序号（永不覆盖） */
    private static Path uniqueTarget(Path dir, String base) {
        Path target = dir.resolve(base + ".md");
        if (!Files.exists(target)) {
            return target;
        }
        for (int i = 2; i < 1000; i++) {
            Path candidate = dir.resolve(base + "-" + i + ".md");
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return dir.resolve(base + "-" + System.currentTimeMillis() + ".md");
    }

    /** 草稿阶段先按当前占用情况给出文件名（确认时会再查一次） */
    private static String uniqueFileName(Path dir, String title) {
        return uniqueTarget(dir, title).getFileName().toString();
    }

    private String newDraftId() {
        byte[] bytes = new byte[3];
        random.nextBytes(bytes);
        return "pn_" + HexFormat.of().formatHex(bytes);
    }

    private static String capContent(String content) {
        String body = content == null ? "" : content.trim();
        if (body.length() > MAX_CONTENT_CHARS) {
            body = body.substring(0, MAX_CONTENT_CHARS) + "\n\n（内容超长，已截断）";
        }
        return body;
    }

    /** 清理过期草稿（顺带执行，防长期驻留） */
    private void purgeExpired() {
        Instant now = Instant.now();
        pendings.values().removeIf(n -> now.isAfter(n.expiresAt()));
    }
}
