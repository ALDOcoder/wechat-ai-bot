# 后端完成情况报告：Vault 门禁 + 文件树 + 保护目录 + 笔记写入确认

> 后端 `wechat-ai-bot` · 提交 `0ec46d4` · 2026-09-06
> 对应需求书：`20260906_vault-filetree-write-confirm-api.md`（v2）
> **结论：A/B/C/D 四项需求全部实现，第 6 节自测清单全部通过，前端可以开始对接。**

---

## 1. 需求完成总览

| 编号 | 需求 | 状态 | 说明 |
| --- | --- | --- | --- |
| A | Vault 密钥门禁 | ✅ 完成 | 密钥换令牌、防爆破、XFF 防伪造，全接口实测 |
| B | Vault 文件树 | ✅ 完成 | 三态 + 基线隐藏 + 折叠节点，实测通过 |
| C | 受保护目录加入/移出索引 | ✅ 完成 | 持久化（重启实测不丢），可逆 |
| D | 笔记写入 + AI 写入确认 | ✅ 完成 | 手动新建 + 两阶段写入（草稿→确认→落盘）全链路实测 |

## 2. 第 7 节 9 个待确认项的答复（已同步写进需求书第 8 节）

| # | 结论 |
| --- | --- |
| 1 | 环境变量名 `RAG_VAULT_KEY` ✅。**未配置时不上锁**（管理面照常可用，启动打 WARN），前端据 `/api/rag/status` 是否 401 决定显示锁屏 |
| 2 | 令牌 30 分钟**滑动续期**（每次成功管理请求刷新）；`remainingSeconds` 首签固定 1800 |
| 3 | ✅ `/api/vault/**` + `/api/rag/**` 全锁。**豁免清单：`/api/vault/session`（换令牌入口）、`/api/vault/note/confirm`、`/api/vault/note/reject`** |
| 4 | ✅ 保护目录机制；yml 键名定为 **`obsidian.protected-dirs`**（起步 `[40-Life]`） |
| 5 | ✅ **confirm/reject 豁免令牌**（拍板采纳）。对冲：草稿路径写死在 agent 输出目录内、confirm 不改路径、落盘时再校验 |
| 6 | ✅ **锁定时聚合数隐藏**（拍板采纳）：status 整体 401，锁屏无任何数字；`/api/health` 不锁，顶栏「服务在线」不受影响 |
| 7 | ✅ 新表 `rag_unlock_path`，重启不丢（实测） |
| 8 | ✅ TTL 10 分钟、confirm 不改路径。**细化：confirm 同名冲突时自动加 -N 序号，以响应实际 path 为准** |
| 9 | ✅ 可行。模型文案已由工具返回值引导（实测回复："已生成笔记草稿，请点击确认卡片保存到知识库。"），**前端只依赖 pendingNotes 字段渲染卡片，勿解析 reply 文案** |

## 3. 自测清单实测结果（对应需求书第 6 节）

**门禁**
- ✅ 无令牌访问 tree / patterns / status / search → 全部 `401 需要密钥`
- ✅ 错误密钥 → `401 密钥不正确`；连错 5 次后第 6 次起 → `429 尝试次数过多，请 10 分钟后再试`（全局兜底 ≥50 次同样实现）
- ✅ 正确密钥 → `{"token":"...","remainingSeconds":1800}`；带令牌访问全部 200；重启后令牌全失效
- ✅ `/api/reply`、`/api/health`、`/api/sessions` 无令牌照常可用

**文件树 / 保护目录**
- ✅ 树中无任何安全基线路径（.mimocode / node_modules / .obsidian 不可见）
- ✅ 未加入索引时 40-Life 仅一个折叠节点 `{"path":"40-Life/","status":"protected","fileCount":20}`，**子文件路径零暴露**
- ✅ `protect/add` → `{"indexed":true,"files":33,"chunks":98}`，子文件以 indexed/excluded 列出
- ✅ `protect/remove` → 子文件消失、索引回落 24 文件；**后端重启后加入状态保持**（实测）
- ✅ `_index.md` / `*moc.md` 在树中为 `excluded`，`rule="导航文件(_index/moc)"`

**笔记 / AI 确认**
- ✅ 手动新建合法路径 → 200 进索引（files 24→25）；重复 → `409 文件已存在：...`
- ✅ `../out.md` → `400 路径不合法：越出库根目录`；`40-Life/hack.md` → `400 落在未加入索引的保护目录内`；`.obsidian/note.md` → `400 安全基线路径不可写入`
- ✅ AI 写笔记 → 回复带 `pendingNotes`（id/path/content）且磁盘无新文件
- ✅ 无令牌 confirm → 200 落盘进索引（files 25→26）；重复 confirm → `404 确认已过期，请让 AI 重新生成`
- ✅ reject → `{"rejected":true}` 且磁盘无文件；**无令牌 confirm/reject 可用**（豁免生效）

## 4. 测试中发现并已修复的问题（前端无需处理，知悉即可）

1. `/api/vault/session` 曾被门禁拦截（自己锁死自己）→ 已加入豁免；
2. **隐私泄漏两处**（测试中暴露，已修复）：40-Life 实际位于 `MyVault/40-Life/` 子目录，最初的目录前缀匹配罩不住 → 改为子串包含语义（与基线/字典规则一致）；`_index.md` 导航判定曾优先于保护目录 → 已改为**保护目录最高优先级**（未加入索引时其下所有路径，含 `_index.md`，一律不可见）；
3. 折叠节点 `fileCount` 大小写不匹配恒为 0 → 已修复（实测 40-Life 计 20 个 md）。

## 5. 前端对接要点（重点看这里）

1. **锁屏判断**：页面加载先调 `GET /api/rag/status`——401 显示锁屏，200 直接进管理面（未配置 RAG_VAULT_KEY 时后端不上锁，永远 200）；
2. **换令牌**：`POST /api/vault/session` 失败响应是纯文本（`密钥不正确` / `尝试次数过多，请 10 分钟后再试`），直接 toast；token 存 `sessionStorage`，请求头 `X-Vault-Token`；
3. **每次 POST/PUT/DELETE（patterns、protect、note、confirm）的响应都自带重建后的最新 files/chunks**，直接刷新状态区，不要再调 refresh；
4. **确认卡片**：渲染 `pendingNotes`（数组，MVP 每次最多 1 条）；确认后系统消息用**响应返回的实际 path**（可能与草稿 path 差一个 `-N` 序号）；confirm/reject 都是**一次性操作**，重复调用得 404，请按一次性处理；
5. **豁免接口**：`/api/vault/note/confirm`、`/api/vault/note/reject` 不需要令牌——聊天流里的确认卡片不依赖解锁状态；
6. `updatedAt` 为本地时间 ISO 字符串（无时区后缀）；管理接口不调用大模型、**不受智谱限流影响**，毫秒级响应；
7. 手动新建笔记落在**字典排除目录**内：允许写入（200）但不进检索索引——前端可在文案上提示"该路径已被排除规则覆盖，不会出现在检索结果中"。

## 6. 边界与遗留（如实告知）

- **需要用户配置 `RAG_VAULT_KEY`**（`setx RAG_VAULT_KEY "你的密钥"` 后重开终端经 start-java.ps1 启动）门禁才生效；未配置 = 管理面不上锁（现状兼容）。start-java.ps1 已支持注册表加载并打印 [OK]/[WARN]；
- 门禁豁免接口（confirm/reject）与聊天面接口一样仅限本机/局域网使用，不要暴露公网；
- 隐私边界不变：排除/锁定只作用于**检索层**，message_log 历史流水与对话记忆里已有的内容不受影响；
- 智谱限流（429/1305）只影响聊天回复，管理面接口完全不受影响；本次联调期间聊天链路偶发限流属正常现象。

## 7. 前端待办（对接清单）

- [ ] 锁屏组件（密钥输入 → POST /api/vault/session → 存 sessionStorage → 倒计时可选）
- [ ] 知识库管理弹窗两页签：文件（树 + 三色 + 40-Life 折叠节点 + 加入/移出 + ＋新建笔记）、规则（原 patterns 卡片平移）
- [ ] 聊天流 pendingNotes 确认卡片（确认/拒绝按钮 → 对应接口 → 刷新文件树）
- [ ] 顶栏「服务在线」继续走 /api/health（未上锁，无需改动）
