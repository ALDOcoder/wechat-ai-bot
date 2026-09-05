# 模型切换（智谱免费 / DeepSeek 付费）开发文档

> 状态：设计中（conversation_setting 表已建，切换逻辑待实现）
> 更新：2026-09-05

## 1. 背景与目标

当前机器人只调用智谱 `glm-4.7-flash`（免费、已验证支持 function calling）。
需求：在 Web 前端可以切换使用 DeepSeek（付费、能力更强），并在同一套对话记忆上
自由来回。

**本期目标**

1. Java 端注册两个模型 Bean：智谱（默认/免费）+ DeepSeek（付费/可选）。
2. `/api/reply` 支持按会话设置选择模型，付费模型只对白名单会话开放。
3. 响应携带实际使用的模型（`provider`），前端标注"当前回答用的是哪个模型"。
4. Web 面板提供模型切换开关，会话偏好持久化到 `conversation_setting`。
5. 两个模型**共享同一套对话记忆**（用户已确认，必须共享，不按模型拆会话）。

**非目标（本期不做）**

- Python 微信端的 `deepseek_friends` 白名单（后续需要时再加，微信端默认走智谱）。

## 2. 关键事实（已验证）

| 项目 | 智谱 Zhipu | DeepSeek 官方 |
|---|---|---|
| base-url | `https://open.bigmodel.cn/api/paas/v4` | `https://api.deepseek.com` |
| completions-path | `/chat/completions`（必须显式覆盖） | `/v1/chat/completions`（Spring AI 默认） |
| 模型 ID | `glm-4.7-flash` | `deepseek-chat` |
| 费用 | 永久免费（QPS 限速，高峰偶发 429） | 按 token 计费（预付费） |
| 环境变量 | `ZHIPU_API_KEY` | `DEEPSEEK_API_KEY` |
| function calling | 已实测通过 | 官方支持（待用有效 key 复测） |

**Spring AI 1.1.8 行为（已从 jar 确认）**

- `OpenAiChatProperties.completionsPath` 默认 `/v1/chat/completions`，
  会直接拼在 `base-url` 后面 → **base-url 一定不能自带 `/v1`**；
  智谱路径不同，必须配置 `spring.ai.openai.chat.completions-path: /chat/completions`。
- `OpenAiApi.builder().baseUrl(...).apiKey(...).completionsPath(...)` 可手工构建。
- `OpenAiChatModel.builder().openAiApi(...).defaultOptions(...)` 可手工构建模型。
- `ChatClient.builder(ChatModel)` 可基于指定模型构建客户端。

## 3. 总体架构

```
Web 前端 (wechat-ai-bot-web / App.vue)
    │  POST /api/reply  { ..., provider: "zhipu" | "deepseek" }
    ▼
WechatBridgeController
    ├─ 读 conversation_setting（preferred_model / allow_deepseek）
    ├─ 权限判定：deepseek 未授权 → 强制回落 zhipu
    ├─ 按 provider 选 ChatClient
    │     ├─ zhipuChatClient   → GLM-4.7-Flash（免费）
    │     └─ deepSeekChatClient → deepseek-chat（付费）
    ├─ 记忆：chat_memory 共享（不区分模型）
    └─ 返回 { reply, ragUsed, provider }
```

### 3.1 Bean 设计

- 新增 `ModelConfig`，注册两个 `OpenAiChatModel` Bean：
  - `zhipuChatModel`（`@Primary`，作为默认，也供 `AiWeChatBot` 等无歧义注入）；
  - `deepSeekChatModel`。
- 需要确认 Spring AI 的 `OpenAiChatAutoConfiguration` 是否在存在自定义
  `OpenAiChatModel` Bean 时自动退出；若不退出则通过
  `spring.autoconfigure.exclude` 排除，避免默认 bean 与自定义 bean 冲突。
- 旧网页版 `AiWeChatBot` 构造器注入单个 `ChatModel`：两个 Bean 会导致歧义，
  用 `@Primary`（智谱）解决，或给该参数加 `@Qualifier`。
- Controller 不再注入自动配置的 `ChatClient.Builder`，改为构造两个
  `ChatClient`（相同系统提示词），按 provider 二选一。

### 3.2 权限与偏好

权限（谁能用付费模型）与偏好（这个会话默认用哪个模型）**分两列存**，都在
`conversation_setting` 一行内：

- `allow_deepseek = 0/1`：付费模型授权，**默认 0（全禁）**，由管理接口显式开启；
- `preferred_model = zhipu/deepseek`：前端"记住我的选择"，默认 `zhipu`。

Web 端当前无登录体系，会话键 = `web:sessionId`（或 `web:IP`）。管理接口只在本机
使用，不暴露公网。

## 4. 数据库

表已建（`schema.sql` 幂等 + 线上库已执行）：

```sql
CREATE TABLE IF NOT EXISTS conversation_setting (
  conversation_id VARCHAR(100) NOT NULL PRIMARY KEY,
  scene           VARCHAR(10)  NOT NULL,   -- friend / group / web
  preferred_model VARCHAR(20)  NOT NULL DEFAULT 'zhipu',
  allow_deepseek  TINYINT(1)   NOT NULL DEFAULT 0,
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

首次请求某会话且表中无记录时：不主动建行，按默认值（zhipu / 禁 deepseek）处理；
只有用户在前端切换或管理接口写值时再 upsert。

## 5. API 设计

### 5.1 `/api/reply`（改动现有）

请求新增字段：

```json
{
  "sender": "web",
  "content": "你好",
  "useRag": true,
  "scene": "web",
  "sessionId": "abc-123",
  "provider": "deepseek"
}
```

- `provider` 缺省时：取 `conversation_setting.preferred_model`，再缺省 `zhipu`。
- 请求 `deepseek` 但 `allow_deepseek=0`：**强制回落 zhipu**，记 WARN 日志。

响应新增字段：

```json
{ "reply": "...", "ragUsed": true, "provider": "deepseek" }
```

### 5.2 会话设置管理端点（新增）

```text
GET /api/conversations/{conversationId}/setting
    → { "conversationId": "...", "scene": "web",
        "preferredModel": "zhipu", "allowDeepseek": false }

PUT /api/conversations/{conversationId}/setting
    body: { "preferredModel": "deepseek", "allowDeepseek": true }
    → 200 { ...最新设置 }
```

实现时与现有 `/api/sessions` 系列端点保持同一 Controller 与包结构。

## 6. 处理流程（/api/reply）

```text
1. 解析 scene / conversationId（沿用现有逻辑：web:sessionId / 好友名 / group:...）
2. 读 conversation_setting：
   - 无记录 → 默认 zhipu、allow_deepseek=false
3. requested = request.provider ?: setting.preferredModel ?: "zhipu"
4. if requested == "deepseek" && !setting.allowDeepseek:
       provider = "zhipu"（记日志：会话 xxx 未授权 deepseek，已回落）
5. 按 provider 选择 chatClient
6. 调模型 → 记忆照常写 chat_memory / message_log
7. 返回 { reply, ragUsed, provider }
```

## 7. 成本与安全

- 默认免费模型，DeepSeek 只在白名单会话可用（服务端强制，不信任前端参数）。
- `/api/reply` 无鉴权：只在 127.0.0.1 使用，不要暴露公网；管理接口同理。
- DeepSeek 预付费：key 无效 / 余额不足会返回 401 / 402。
- **决策点（推荐）**：DeepSeek 调用失败时自动回落智谱一次并在响应标注
  `provider: "zhipu"` + 追加提示，避免用户等半天只拿到报错；是否采纳实现时确认。

## 8. 涉及文件

后端（仓库 `ALDOcoder/wechat-ai-bot`）：

```text
src/main/java/com/example/wechataibot/
├── config/ModelConfig.java                    # 新增：双 OpenAiChatModel Bean
├── persistence/entity/ConversationSetting.java # 新增：实体
├── persistence/mapper/ConversationSettingMapper.java # 新增：Mapper
├── persistence/ConversationSettingService.java # 新增：读 / upsert
└── web/WechatBridgeController.java            # 改：双 ChatClient + provider +
                                               #    setting 检查 + 管理端点
src/main/resources/application.yml             # 改：自动配置排除说明 / 保留智谱默认
src/main/resources/schema.sql                  # 已含 conversation_setting
```

前端（仓库 `ALDOcoder/wechat-ai-bot-web`）：

```text
src/App.vue    # 改：模型切换开关（智谱/DeepSeek）+ 消息气泡标注 provider
```

## 9. 实施步骤

1. 验证 Spring AI `OpenAiChatAutoConfiguration` 的条件注解行为，确定
   `ModelConfig` 写法（排除 or 自动退出）。
2. `ModelConfig` 双 Bean + `@Primary`，编译通过，启动无 bean 歧义。
3. `ConversationSetting` 实体 / Mapper / Service。
4. Controller：provider 选择 + 权限回落 + 响应字段 + 设置管理端点。
5. 手工验证：
   - 默认请求 → `provider: "zhipu"`；
   - `provider: "deepseek"` 未授权 → 回落 zhipu；
   - PUT 授权后 → 真走 deepseek；
   - deepseek key 无效/没钱 → 401/402 处理符合第 7 节决策。
6. 前端 App.vue 开关 + provider 标注（前端仓库独立提交）。
7. 更新本文档状态并提交推送。

## 10. 风险与待确认

- **DeepSeek key / 余额**（阻塞项）：`DEEPSEEK_API_KEY` 当前未配置，
  需要有效 key 且账户有余额；实现前先用最小请求实测。
- OpenAI 自动配置与自定义 Bean 的兼容性：第 1 步验证后定方案。
- `glm-4.7-flash` 高峰 429：依赖 Spring AI retry / 提示用户稍后重试。
- DeepSeek 失败是否自动回落智谱：见第 7 节决策点。
