# RAG 设置卡片 · 前端对接文档（排除规则数据字典）

> 状态：后端已实现并验证通过（commit cd4e2d6）
> 更新：2026-09-06
> 后端仓库：ALDOcoder/wechat-ai-bot　前端仓库：ALDOcoder/wechat-ai-bot-web
>
> ⚠️ **门禁变更（2026-09-06 v2）**：配置 `RAG_VAULT_KEY` 后，本页所有 /api/rag/** 接口
> 要求请求头 `X-Vault-Token`（密钥经 POST /api/vault/session 换取），无令牌返回 401「需要密钥」。
> 详见《20260906_vault-filetree-write-confirm-api.md》第 3.1 节。未配置密钥时不上锁（现状）。

## 1. 功能背景

Obsidian 知识库检索（RAG）的排除规则原本只在 `application.yml` 静态配置。
现已升级为 **MySQL 数据字典表**（`rag_exclude_pattern`），前端可通过 REST 接口
增删改，任何变更**自动重建索引、全局立即生效**，用于保护真正隐私的笔记目录。

**生效语义（重要）**：

```
实际排除规则 = basePatterns（yml 基线，4 条） ∪ patterns 中 enabled=true 的规则（并集）
```

- `basePatterns`（.mimocode / node_modules / .obsidian / 40-Life）**不可通过 API 移除**，
  前端只能展示为只读，UI 上标注"基线"；
- 删除/停用字典规则永远不会意外放开隐私目录；
- 匹配方式：相对路径**子串**包含、忽略大小写（如 `Templates` 会挡住所有含该词的路径）。

## 2. 接口契约

所有接口无鉴权（仅限 127.0.0.1 / Vite 代理），不调用大模型、**不受智谱限流影响**，
响应都是毫秒级。除 GET 外均为 `Content-Type: application/json`（中文务必 UTF-8）。

### 2.1 GET /api/rag/patterns — 规则列表

```json
{
  "basePatterns": [".mimocode", "node_modules", ".obsidian", "40-Life"],
  "patterns": [
    {
      "id": 1,
      "pattern": "50-Resources/Templates",
      "remark": "模板占位符，无检索价值",
      "enabled": true,
      "updatedAt": "2026-09-06T14:18:18.100"
    }
  ]
}
```

### 2.2 POST /api/rag/patterns — 新增规则

请求体：

```json
{ "pattern": "私人目录", "remark": "私人日记，勿检索" }
```

成功 `200`（row 为新规则，files/chunks 为重建后的最新索引数）：

```json
{
  "row": { "id": 2, "pattern": "私人目录", "remark": "私人日记，勿检索",
           "enabled": true, "updatedAt": "..." },
  "files": 21, "chunks": 70, "lastError": ""
}
```

失败 `400`（响应体为纯文本提示，直接 toast 即可）：

| 场景 | 文案 |
| --- | --- |
| pattern 为空/纯空格 | `pattern 不能为空` |
| pattern 超 200 字 | `pattern 超过 200 字` |
| remark 超 200 字 | `remark 超过 200 字` |
| 重复添加 | `规则已存在：私人目录` |

### 2.3 PUT /api/rag/patterns/{id} — 更新（启停 / 备注，允许只传一个字段）

```json
{ "enabled": false }
```
或
```json
{ "remark": "新备注" }
```

成功 `200`：`{ "row": {...}, "files": ..., "chunks": ..., "lastError": "" }`
（停用后 row.enabled=false，该规则立即失效，索引恢复包含对应文件）
`404`：id 不存在；`400`：校验失败。

### 2.4 DELETE /api/rag/patterns/{id} — 删除规则

成功 `200`：`{ "deleted": true, "files": ..., "chunks": ..., "lastError": "" }`
`404`：id 不存在。只能删除数据字典内的规则，yml 基线不受任何 API 影响。

### 2.5 配套接口（卡片其他区块用）

```
GET /api/rag/status   → { "enabled":true, "vaultPath":"C:\\...\\webflow",
                          "files":22, "chunks":77, "lastError":"" }
POST /api/rag/refresh → 手动重建索引（一般用不到：增删改已自动重建）
GET /api/rag/search?q=关键词 → 调试检索，可做"试一试"小功能
```

## 3. UI 建议（RAG 设置卡片）

```
┌─ RAG 知识库设置 ────────────────────────────┐
│ 索引状态：✅ 启用 · 22 文件 · 77 块          │  ← GET /api/rag/status
│                                              │
│ 基线规则（不可移除）：                        │
│   [.mimocode] [node_modules] [.obsidian] [40-Life] │  ← 只读 chips
│                                              │
│ 自定义规则：                                  │  ← GET /api/rag/patterns
│   ┌────────────────────────────────┐        │
│   │ 私人目录  私人日记，勿检索  [开关] [删] │        │  ← PUT / DELETE
│   └────────────────────────────────┘        │
│   [输入排除路径...] [备注(可选)] [＋添加]     │  ← POST
└──────────────────────────────────────────────┘
```

交互要点：

1. **每次 POST/PUT/DELETE 的响应里就带着最新的 files/chunks**——直接用它刷新状态区，
   不要再额外调一次 refresh；
2. 删除前弹确认框（删了规则 = 对应隐私目录会重新进入检索范围）；
3. 400 的响应体是纯文本，直接作为错误提示展示；404 说明规则已被别处删除，刷新列表即可；
4. `updatedAt` 是本地时间 ISO 格式（无时区后缀），展示时可 `new Date(x).toLocaleString()`；
5. pattern 输入建议 placeholder 提示"路径包含匹配，如：私人目录 / 40-Diary"。

## 4. curl 自测样例（Windows PowerShell 注意中文需 UTF-8）

```bash
curl http://127.0.0.1:8080/api/rag/patterns
curl -X POST http://127.0.0.1:8080/api/rag/patterns \
  -H "Content-Type: application/json; charset=utf-8" \
  -d '{"pattern":"私人目录","remark":"私人日记"}'
curl -X PUT http://127.0.0.1:8080/api/rag/patterns/1 \
  -H "Content-Type: application/json" -d '{"enabled":false}'
curl -X DELETE http://127.0.0.1:8080/api/rag/patterns/1
```

## 5. 关联功能：Agent 写笔记（同卡片可顺带展示）

web 端聊天里 AI 可调用 `writeNote` 工具把内容写入库内
`obsidian.agent-output-dir` 目录（默认 `90-Agent`，yml 可改，留空禁用），
写入后自动进索引。该目录默认**可被检索**；若想屏蔽，用上面的字典接口
添加 `90-Agent` 排除规则即可——两个功能正好配合使用。

隐私边界（如实告知用户）：排除只作用于**检索层**，历史消息流水
（message_log）与对话记忆里已有的内容不受影响。
