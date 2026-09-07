# 项目总结与 AI 工作记录

> 更新：2026-09-06 · 覆盖提交至 `0ec46d4`
> 本文分两部分：**一、项目本身是什么、怎么运转**；**二、AI 助手在 2026-09-06 会话里做了什么**（含实现明细、发现的 bug、遗留事项）。

---

## 一、项目总结

### 1.1 一句话

**个人微信 AI 自动回复机器人**：Java（Spring Boot 3 + Spring AI 1.1.8）负责"思考"（调大模型、记忆、RAG、Agent 工具），Python（wxauto）负责微信消息收发，另有独立 Web 前端仓库（`wechat-ai-bot-web`）提供网页聊天与知识库管理面。

```
微信 PC 客户端 ──wxauto 轮询──> python/wechat_bridge_4x_free.py ──POST /api/reply──> Java 后端(8080)
Web 前端 (Vite 代理) ──────────────────────────────────────────────┘
                                      │
                    三模型路由（智谱免费×2 / DeepSeek 付费）
                    对话记忆（窗口+滚动摘要）· RAG 检索 · Agent 工具
                                      │
                                   MySQL 持久化
```

### 1.2 核心能力

**① 三模型路由**（`ModelConfig` + `WechatBridgeController`）

- `zhipuChatModel`（glm-4.7-flash，免费默认，@Primary）、`glm4FlashChatModel`（glm-4-flash，免费备选）、`deepSeekChatModel`（付费）；三个都是 OpenAI 兼容协议手工构建的 Bean，智谱必须覆盖 `completionsPath`（默认 `/v1/...` 会 404）。
- 选择优先级：请求 `provider` > `conversation_setting.preferred_model` > 默认 zhipu；DeepSeek 需 `allow_deepseek=1` 授权否则强制回落；DeepSeek 调用失败自动回落智谱；响应带实际 `provider`。
- 排除了 Spring AI 的 OpenAI 自动配置系列，避免 Bean 冲突。

**② 对话记忆（两层）**（`MemoryConfig` + `ConversationSummaryService`）

- 滑动窗口：`MessageWindowChatMemory` + 自研 MySQL 仓库，窗口可配置（`wechat.bot.memory.max-messages`，默认 40 条）；
- 滚动摘要：滑出窗口的旧对话由**免费智谱模型异步**（单线程守护池、按会话去重）压缩成 ≤500 字要点，存 `conversation_summary`（含游标 `last_message_id`），每次请求以 SystemMessage 注入——"窗外不失忆"；
- 摘要原料来自 `message_log` 全量流水（自动过滤系统兜底等噪音行），增量 merge（新摘要 = LLM(旧摘要 + 新出窗批次)）；
- "清空记忆"同时重置窗口与摘要游标；写库前复核游标**防并发竞态**（见 2.2）。

**③ Obsidian RAG 知识库**（`VaultIndexService` / `KeywordSearchService`）

- 启动扫描 markdown → 标题/段落切块 → 内存关键词索引；`/api/rag/search` 调试检索；
- **路径判定统一走 `classify()`**，索引与文件树共用一套规则，三层按序生效：
  1. **安全基线**（yml `exclude-patterns`：.mimocode / node_modules / .obsidian）——不索引、任何接口不可见、**不可通过 API 解锁**；
  2. **排除规则字典**（表 `rag_exclude_pattern`，`/api/rag/patterns` 前端增删改，改后自动重建）；
  3. **受保护目录**（yml `obsidian.protected-dirs`，起步 `[40-Life]`）——默认不索引、文件树只显示折叠节点不露子文件名；持管理令牌可 `protect/add|remove` 加入/移出索引（`rag_unlock_path` 表持久化，可逆）。

**④ 知识库管理面门禁**（`VaultTokenService` / `VaultGateInterceptor` / `VaultController`）

- 环境变量 `RAG_VAULT_KEY`（**未配置 = 不上锁**，兼容现状）换 30 分钟滑动续期令牌；密钥常量时间比较；
- 防爆破：单 IP 连错 5 次锁 10 分钟 + 全局兜底（5 分钟 ≥50 次全服锁）；XFF 仅在本机可信代理时采信（防伪造头绕锁）；
- 门禁范围 `/api/vault/**` + `/api/rag/**`；豁免：`/api/vault/session`（换令牌入口）、`/api/vault/note/confirm|reject`（AI 写入确认不打断聊天流，拍板项）；
- 管理面能力：文件树（indexed/excluded/protected 三态）、保护目录开关、手动新建笔记、检索调试。

**⑤ Agent 工具与两阶段笔记写入**（`GeneralAgentTools` / `KnowledgeAgentTool` / `ObsidianNoteWriterTool` / `VaultNoteService`）

- 工具由模型自主调用（function calling）：当前时间、知识库检索（按 RAG 开关注册）、笔记写入（仅 web 场景 + RAG 开启）；
- 笔记写入走**两阶段**：AI 生成草稿（`pendingNotes` 随回复下发，10 分钟 TTL，不落盘）→ 用户网页确认 `confirm` 才写盘进索引 / `reject` 丢弃；
- 护栏：只写 `obsidian.agent-output-dir`（默认 `90-Agent`，留空禁用）、文件名清洗防路径穿越、同名自动 `-N` 序号永不覆盖、单篇 2 万字符截断。

**⑥ 持久化与会话管理**

| 表 | 用途 |
| --- | --- |
| `chat_memory` | 每会话窗口内消息（JSON，整段覆盖写） |
| `message_log` | 全量收发流水（含 provider 列、use_rag），会话列表/历史/删除基于它 |
| `conversation_setting` | 会话模型偏好 + DeepSeek 授权 |
| `conversation_summary` | 滚动摘要 + 游标 |
| `rag_exclude_pattern` | 排除规则字典 |
| `rag_unlock_path` | 受保护目录"已加入索引"状态 |

`/api/sessions*` 会话列表/历史/删除（三表级联清理），`/api/conversations/{id}/setting` 模型设置管理。

**⑦ Python 端**：4.x 免费轮询（`wechat_bridge_4x_free.py`，主用）、4.x Plus、3.x（已废）；回复范围白名单走 `config.ini`。

### 1.3 关键文档索引

- `README.md` — 快速开始、风险提示
- `docs/OVERVIEW.md` / `docs/ROADMAP.md` — 架构与规划
- `docs/MODEL-SWITCH.md` — 多模型切换设计
- `docs/PROJECT-SUMMARY.md` — 本文
- `agent/zhipu/20260906_rag-patterns-api.md` — RAG 设置卡片前端契约
- `agent/zhipu/20260906_vault-filetree-write-confirm-api.md` — 门禁/文件树/两阶段写入需求书（**第 8 节为后端答复**）
- `agent/zhipu/20260906_vault-completion-report.md` — 该需求完成情况报告（给前端）
- `reset-vault-key.ps1` — 管理密钥生成/重置脚本；`start-java.ps1` — 一键启动（自动加载环境变量）

---

## 二、AI 助手工作记录（2026-09-06 会话）

### 2.1 解答与决策支持（无代码）

- 项目结构、多模型路由机制、Bean 启动加载时机（饿汉单例、启动不联网、key 只读一次）、记忆窗口大小评估（给出"40 条窗口 + 滚动摘要"的分层建议）；
- Obsidian 索引数与文件数不一致排查（定位到 `_index.md`/`*moc.md` 导航文件跳过逻辑，非漏索引）。

### 2.2 提交 `805d19b`：对话记忆增强（窗口可配置 + 滚动摘要）

- **实现**：`MemoryProperties`（40 条默认可配置）；`ConversationSummaryService`（游标 + 增量 merge + 异步单线程 + 免费模型 + 噪音过滤）；Controller 注入摘要 SystemMessage、清空记忆/删会话联动；`schema.sql` +`conversation_summary`。
- **验证**：连发造积压后摘要自动生成（日志：压缩 22 条→1 条→4 条增量推进）；**端到端**——第 1 轮埋的暗号滑出 20 轮窗口后仍能答对（只能来自摘要）；清空记忆联动重置实测。
- **发现并修复真 bug（竞态）**：异步摘要任务与"清空记忆"并发时，清空前启动的任务会把旧摘要写回，"复活"已清空的记忆（日志实锤）。修复：**存库前复核游标**，被并发重置则丢弃本次结果。

### 2.3 提交 `cd4e2d6`：RAG 排除规则字典化 + Agent 写笔记

- **实现**：`rag_exclude_pattern` 表 + `/api/rag/patterns` 四端点（变更自动重建、响应自带最新 files/chunks）；`ObsidianNoteWriterTool`（writeNote → `90-Agent/`）；`MemoryConfig`/yml 配套。
- **验证**：加 `50-Resources/Templates` 索引 22→14（8 模板精确挡掉）；重复 400；同名写入自动 `-2` 不覆盖；搜索即时命中新笔记。
- **发现的真实行为**：function calling 多步调用中，若写笔记成功、最终回复撞限流，会出现"提示服务不可用但笔记已落盘"——如实告知前端留意。

### 2.4 提交 `0ec46d4`：门禁 + 文件树 + 保护目录 + 两阶段写入

- **审查前端需求书**（9 个待确认项逐项答复，写入需求书第 8 节；两个拍板项由用户定：confirm/reject 豁免令牌、锁定时隐藏聚合数）；
- **实现**：`VaultTokenService`（令牌 + 防爆破 + XFF 防伪造）、门禁拦截器、`VaultController`（session/tree/protect/note）、`RagProtectService`、`VaultNoteService` 两阶段写入、`ReplyResponse` 扩展 `pendingNotes`、`classify()` 统一判定重构；
- **审查发现的关键迁移**：40-Life 必须从 yml 基线挪到 `obsidian.protected-dirs`，否则"加入索引"永远无效（基线优先）；
- **测试暴露并修复 3 个真 bug**：
  1. `/api/vault/session` 被自己的门禁拦截（换不了令牌）→ 加豁免；
  2. **隐私泄漏**：40-Life 实际在 `MyVault/40-Life/` 子目录，前缀匹配罩不住（私密聊天一度进索引）→ 改子串包含语义；`_index.md` 导航判定优先级高于保护目录（泄漏聊天对象名路径）→ 改为保护目录最高优先级，折叠节点外零暴露；
  3. 折叠节点 `fileCount` 大小写不匹配恒 0 → 修复；
- **自测清单**：需求书第 6 节全部通过（门禁 401/429、树三态、保护目录持久化、手动建文 200/409/400×3、两阶段写入全链路）。

### 2.5 文档与运维

- 文档：RAG 设置卡片契约、需求书第 8 节答复、完成情况报告（给前端）、本文；
- 运维：4 次功能提交（另有一次 glm4flash 三模型提交 `ed8649f` 为用户改动，AI 复核后入库）、后端多次重启、`reset-vault-key.ps1` 审查（修 `setx` 空格截断）并执行——生成管理密钥 `8ruV2ZIY...`（已持久化注册表，门禁生效验证 401）。

### 2.6 遗留事项 / 已知边界

1. **provider 命名不一致（待办）**：Controller 校验已认 `glm-4.7flash`（用户改动），但 `ConversationSettingService.normalize` 仍归一 `zhipu/glm4flash/deepseek`——前端传 `glm-4.7flash` 会被存成 `zhipu`，建议对齐；
2. 免费模型高峰限流（429/1305）常态化：聊天链路偶发"服务不可用"兜底属正常；管理面接口不受影响；
3. AI 写笔记的"回复失败但草稿已落盘"行为（工具成功 + 回复失败），前端提示需兼容；
4. 摘要为要点式压缩，细节保真度随时间递减（近期 40 条仍是原文）；
5. 隐私边界：一切排除/锁定只作用于**检索层**，message_log 历史流水与对话记忆已有内容不受影响；
6. 本地提交领先 origin（截至本文 5 个），推送由用户决定；
7. `/api/reply` 等聊天面接口无鉴权，仅限本机/局域网。

---

## 附：快速上手

```powershell
# 一次性：数据库初始化（sql/init.sql）+ 设置环境变量（用户级）
setx ZHIPU_API_KEY "sk-..."          # 智谱（必需）
setx DEEPSEEK_API_KEY "sk-..."       # DeepSeek（可选）
setx RAG_VAULT_KEY "你的管理密钥"     # 知识库管理面门禁（不设=不上锁）
setx OBSIDIAN_VAULT_PATH "C:\你的库"  # RAG 知识库路径
# 忘记管理密钥：重跑 reset-vault-key.ps1 换新的

# 日常启动
.\start-java.ps1                     # Java 后端（新窗口常驻）
cd python; python wechat_bridge_4x_free.py   # 微信桥接
```
