# 新增模型 GLM-4-Flash（provider = `glm4flash`）· 接口文档

> 生成时间：2026-09-05
> 适用范围：后端 `wechat-ai-bot`（Spring-AI），指导 Web 前端 / Python 桥接端接入第三个模型
> 后端已完成并实测通过；**前端暂不改动**，本文档作为接入指南留存。

---

## 一、背景与模型清单

原架构为双模型（智谱 GLM-4.7-Flash 免费 + DeepSeek 付费）。GLM-4.7-Flash 在高峰期
会出现 429（code 1305「该模型当前访问量过大」），因此新增同平台的免费模型
**GLM-4-Flash** 作为免费备选通道，与主力模型共用同一个 `ZHIPU_API_KEY`。

| provider 值 | 实际模型 ID | 计费 | 是否需要授权 | 定位 |
|---|---|---|---|---|
| `zhipu`（默认） | `glm-4.7-flash` | 免费 | 否 | 主力免费模型 |
| `glm4flash`（新增） | `glm-4-flash` | 免费 | 否 | 免费备选，主力 429 时切换 |
| `deepseek` | `deepseek-chat` | 按量付费 | **是**（`allow_deepseek=1`） | 付费高质量通道 |

三模型在 `ModelConfig.java` 中均为独立 Bean：`zhipuChatModel` / `glm4FlashChatModel` /
`deepSeekChatModel`，系统提示词、对话记忆（chat_memory）、工具（function calling）完全共享。

---

## 二、接口变更点

### 1. POST /api/reply —— 请求体 `provider` 新增合法值

```jsonc
{
  "sender": "web",
  "content": "你好",
  "useRag": true,
  "scene": "web",              // friend | group | web
  "sessionId": "abc123",       // web 渠道的会话 id
  "provider": "glm4flash"      // zhipu | glm4flash | deepseek（缺省=会话偏好，再缺省 zhipu）
}
```

- 兼容别名：传 `glm-4-flash` 也会归一化为 `glm4flash`；未知值一律按 `zhipu` 处理。
- **响应体 `provider` 字段可能返回 `"glm4flash"`**，前端/桥接端按未知模型兜底显示的
  逻辑需要能容下这个值（当前前端只认 zhipu/deepseek，故暂不改动前端）。

响应示例（实测）：

```jsonc
// 请求 provider=glm4flash
{"reply":"十","ragUsed":false,"provider":"glm4flash"}
```

### 2. PUT /api/conversations/{conversationId}/setting —— `preferredModel` 新增合法值

```jsonc
PUT /api/conversations/web:abc123/setting
{ "scene": "web", "preferredModel": "glm4flash", "allowDeepseek": false }
```

- `preferredModel` 现在接受 `zhipu` / `glm4flash` / `deepseek` 三值，其余 400。
- 设置后，该会话**不带 provider 参数**的 /api/reply 请求都会走偏好模型（实测通过）。
- `GET` 同路径返回的 `preferredModel` 也可能是 `glm4flash`。

### 3. 落库与流水

- `conversation_setting.preferred_model` 可存 `glm4flash`（无需迁移表结构）。
- `message_log.provider` 对 AI 回复会记录实际模型（`glm4flash`），按模型统计报表时注意三值。

---

## 三、调用示例

### curl：指定 glm4flash 发一条消息

```bash
curl -X POST http://127.0.0.1:8080/api/reply \
  -H "Content-Type: application/json" \
  -d '{"sender":"web","content":"用一句话介绍你自己","scene":"web","sessionId":"s1","provider":"glm4flash"}'
```

### 前端 fetch（将来接入时参考）

```js
// 切换模型按钮的选项（App.vue 的 MODELS 数组加一项即可）
const MODELS = [
  { id: 'zhipu',     label: 'GLM-4.7', tip: '智谱 GLM-4.7-Flash · 免费' },
  { id: 'glm4flash', label: 'GLM-4',   tip: '智谱 GLM-4-Flash · 免费' },
  { id: 'deepseek',  label: 'DeepSeek', tip: 'deepseek-chat · 按量付费' }
]

// 1) 发消息：provider 直接传 model.value，后端已支持
body: JSON.stringify({ sender:'web', content:text, scene:'web', sessionId:id, provider:model.value })

// 2) 读会话偏好：preferredModel 三值都要认
model.value = ['zhipu','glm4flash','deepseek'].includes(data.preferredModel)
  ? data.preferredModel : 'zhipu'

// 3) 消息气泡标签（App.vue 模板）加一个分支 + styles.css 加 .model-tag.g4 样式
<span v-else-if="msg.provider === 'glm4flash'" class="model-tag g4">GLM-4</span>

// 4) 回落判定不变：只有 deepseek 才谈「回落免费」
fallback: model.value === 'deepseek' && data.provider !== 'deepseek'
```

---

## 四、权限与回落规则（不变）

1. 优先级：**请求显式 provider > 会话偏好 > 默认 zhipu**。
2. `deepseek` 仍需会话授权（`allow_deepseek=1`），未授权强制回落 `zhipu`（日志可查）。
3. `zhipu` / `glm4flash` 均免费，**无需授权**，任何会话可直接用。
4. deepseek 调用失败（余额不足/网络）→ 自动回落 `zhipu` 一次；响应 `provider` 标注实际模型。
5. 「清空记忆」「清空记忆外的记忆命令」等控制消息与模型无关，不受影响。

---

## 五、注意事项

- ⚠️ `glm-4-flash` 是较早期模型，能力弱于 `glm-4.7-flash`，建议只作为 429 高峰的
  降级通道，日常仍用默认智谱。
- 智谱两个免费模型共用 `ZHIPU_API_KEY` 与限流额度，若账号级限流，切 glm4flash 未必
  能绕开（429 分模型级/账号级，实测单模型拥塞时另一模型可用）。
- key 依旧只存在于环境变量，**不要写进任何配置文件提交**。
- 本接口无鉴权，仅限本机使用。
