# 个人微信 AI 自动回复机器人（Java 逻辑 + Python 收发）

**Java（Spring Boot 3 + Spring AI）只负责“思考”，Python（wxauto）负责微信消息收发。**

> 📖 项目方向与长期规划请看 [路线图](docs/ROADMAP.md)；完整架构与排障请看 [全面介绍文档](docs/OVERVIEW.md)。

收到微信文本消息后，Python 把消息 POST 给 Java 服务，Java 调用**远端 HTTPS 大模型接口**（默认 DeepSeek，兼容 OpenAI），再把 AI 回复交给 Python 发回微信。

支持**多轮对话记忆**：Java 端用 Spring AI 的 ChatMemory（滑动窗口，每会话保留最近 20 条消息），
每个聊天对象独立上下文，发“清空记忆”可重置当前对话。

支持 **Obsidian 知识库问答（关键词检索 RAG）**：配置库路径后，提问会自动检索相关笔记并据此回答。

```
微信 PC 客户端
   |  wxauto 监听新消息（3.x 免费版 或 4.x Plus 版）
   v
python/wechat_bridge_3x.py（或 _4x.py）
   |  POST http://127.0.0.1:8080/api/reply  {"sender","content"}
   v
Java：Spring Boot + Spring AI → DeepSeek/OpenAI（远端 HTTPS，严禁本地模型）
   |  返回 {"reply":"..."}
   v
wx.SendMsg(reply, who=...) 发回微信
```

> 为什么要换成这个架构：原来的 wechat-api（网页版协议）方案登录时被微信服务器拒绝
> （`webwxnewloginpage → <error><ret>1203</ret><message>Unable to log in</message></error>`），
> 1203 表示“该微信号不能登录网页微信”，属于账号级限制，客户端代码无法绕过。
> wxauto 走的是 PC 客户端 UI 自动化，绕开了网页版协议限制。

---

## 一、两条路线怎么选

| 方案 | 微信客户端版本 | Python 库 | 费用 | 说明 |
| --- | --- | --- | --- | --- |
| **3.x** | 3.9.8.15 | `wxauto`（已 vendored 到 `python/wxauto/`） | 免费 | ⚠️ 2026 年起腾讯已拦截旧版本登录（提示“版本过低”），**新设备基本无法登录**，此路线已基本作废 |
| **4.x 免费轮询** | 4.x（你现在的 4.1） | `wxauto4`（免费） | 免费 | 没有监听接口，用 `GetSession` 轮询“有新消息的会话”实现自动回复，稳定性和速度略差，但零成本 |
| **4.x Plus** | 4.x | `wxautox4`（付费） | 需要激活码 | 官方适配 4.x，监听接口完整、体验最好；激活码需在 wxauto 官网购买 |

你现在是微信 4.1：**免费优先用 `python/wechat_bridge_4x_free.py`（wxauto4 轮询）**，想要更稳更快再考虑购买 `wxautox4` 激活码走 `wechat_bridge_4x.py`。3.x 方案因为腾讯拦截旧版本登录，已经不建议折腾。

---

## 二、环境要求

| 组件 | 版本 |
| --- | --- |
| JDK | 17+ |
| Maven | 3.8+ |
| Python | 3.9 ~ 3.12（4.x 的 wxautox4 支持到 3.13） |
| 操作系统 | 仅 Windows 10/11（wxauto 是 Windows UI 自动化，Mac 不支持） |
| 微信客户端 | 3.9.8.15（3.x 路线）或 4.x（4.x 路线） |

---

## 三、快速开始

### 1. 准备一个【测试小号】

⚠️ 个人微信自动化违反微信用户协议，账号有被限制/封禁风险，**务必使用测试小号**，不要用主号。

### 2. 配置 DeepSeek API Key（环境变量，不要写进 yml）

```powershell
# Windows PowerShell
$env:DEEPSEEK_API_KEY = "sk-你的key"
```

`src/main/resources/application.yml` 中的占位配置（默认值 `sk-REPLACE_ME`，不配置会 401）：

| 配置项 | 示例值 | 说明 |
| --- | --- | --- |
| `spring.ai.openai.base-url` | `https://api.deepseek.com` | 远端 HTTPS 接口，**严禁本地 Ollama** |
| `spring.ai.openai.api-key` | `${DEEPSEEK_API_KEY:sk-REPLACE_ME}` | API Key，推荐环境变量注入 |
| `spring.ai.openai.chat.options.model` | `deepseek-chat` | 模型名（DeepSeek 也可用 `deepseek-reasoner`） |
| `spring.ai.openai.chat.options.temperature` | `0.7` | 回答随机性 |
| `server.port` | `8080` | Java 服务端口（Python 默认连它） |

### 3. 启动 Java 服务（逻辑端）

```powershell
mvn spring-boot:run
```

启动后会出现 Tomcat 端口 8080，验证：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/health
# 期望输出 status = ok
```

> Java 日志同时会写入项目根目录 `logs/wechat-ai-bot.txt`（控制台照常输出），方便回看每次消息与 AI 回复。

### 3.5 启用 Obsidian 知识库问答（可选）

```powershell
$env:OBSIDIAN_VAULT_PATH = "C:\你的Obsidian库路径"
```

重启 Java 后自动建立索引，可用这些接口验证：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/rag/status      # 索引状态
Invoke-RestMethod "http://127.0.0.1:8080/api/rag/search?q=金蝶苍穹"  # 测试检索
```

笔记变更后执行 `Invoke-RestMethod -Method Post http://127.0.0.1:8080/api/rag/refresh` 重建索引。
默认排除 `.mimocode` / `node_modules` / `.obsidian` / `40-Life`（私人聊天），可用
`application.yml` 的 `obsidian.exclude-patterns` 调整。

> 默认 `wechat.bot.enabled=false`，旧的网页版微信登录已关闭，Java 端不会再弹二维码、不会创建 assets 二维码图片。

### 4. 安装 Python 依赖

```powershell
cd python
pip install -r requirements.txt
```

（wxauto 3.9 源码已放在 `python/wxauto/`，无需再从 GitHub 安装；4.x 的 `wxautox4` 需单独 `pip install wxautox4` 并激活。）

### 5. 启动 Python 桥接端

**3.x 路线**（微信已降级到 3.9.8.15，先打开微信并登录）：

```powershell
cd python
python wechat_bridge_3x.py
```

**4.x 免费路线**（微信 4.x，无需降级、无需付费）：

```powershell
cd python
python wechat_bridge_4x_free.py
```

> 该脚本默认使用**预览轮询模式**：只看会话列表的最新消息，不打开聊天窗口、
> 不读完整消息，从而绕开 wxauto4 免费版“解析发送者无限重试”的卡死问题，速度也更快。
> 注意：预览模式拿不到发送者、也无法区分群聊，群消息会按私聊处理——
> 不想回复群，请把群名加入 `config.ini` 的 `skip_chats`，或用 `friends` 白名单限制。
> 旧模式（打开聊天窗口读完整消息）可用 `--read-messages` 开启。

**4.x Plus 路线**（微信 4.x + wxautox4 已激活）：

```powershell
cd python
python wechat_bridge_4x.py
```

看到 `[INFO] 开始监听新消息` 后，用另一个微信号给测试小号发条文本消息，就会收到 AI 回复。

### 6. 常用参数

| 参数 | 说明 |
| --- | --- |
| `--ping` | 只检查 Java 服务是否在线 |
| `--reply-groups` | 群消息也自动回复（默认只回私聊） |
| `--interval 2` | 轮询间隔秒数 |
| `--listen 张三 工作群` | （3.x）只监听指定对象 |
| `--java-url http://127.0.0.1:8080` | 指定 Java 服务地址 |

环境变量：`BOT_JAVA_URL` / `BOT_POLL_INTERVAL` / `BOT_AI_TIMEOUT`。

### 7. 回复范围配置（指定好友 / 群消息开关）

编辑 [python/config.ini](python/config.ini)（改完重启脚本生效）：

```ini
[reply]
friends = 姐姐, 张三      # 只回复这些好友；留空 = 回复所有私聊
groups = false            # true = 回复群消息，false = 不回复（默认）
group_names = 工作群       # 开启 groups 后，只回复这些群；留空 = 回复所有群
```

也可以用命令行临时覆盖（优先级更高）：

```powershell
python wechat_bridge_4x_free.py --reply-friends 姐姐,张三 --reply-groups --group-names 工作群
```

---

## 四、目录结构

```
Spring-AI/
├── pom.xml                         # Maven 依赖（Spring Boot Web + Spring AI + okhttp 等）
├── src/main/resources/application.yml
├── src/main/java/com/example/wechataibot/
│   ├── WechatAiApplication.java    # Spring Boot 启动类
│   ├── web/WechatBridgeController.java  # 核心桥接接口 POST /api/reply
│   ├── bot/                         # 旧网页版方案（默认关闭，可忽略）
│   └── config/WeChatBotProperties.java
├── python/
│   ├── wechat_bridge_3x.py         # 3.x 路线：wxauto 3.9 轮询 + 回复（旧版本已基本无法登录）
│   ├── wechat_bridge_4x_free.py    # 4.x 免费路线：wxauto4 轮询会话 + 回复
│   ├── wechat_bridge_4x.py         # 4.x Plus 路线：wxautox4 回调监听 + 回复（需激活码）
│   ├── bot_config.py               # 回复范围配置加载（config.ini）
│   ├── config.ini                  # 回复范围配置：好友白名单 / 群开关 / 群白名单
│   ├── requirements.txt
│   └── wxauto/                     # vendored 的 wxauto 3.9 源码（MIT）
```

---

## 五、风险提示（务必阅读）

1. **账号风险**：个人微信自动化违反《微信软件许可及服务协议》，可能被限制登录、封号。**只用测试小号**，不要用主号。
2. **风控**：wxauto 是模拟人工操作客户端，高频收发容易触发风控。默认每轮间隔 2 秒、每次发送后额外停顿 1 秒，请勿调得太快。
3. **隐私**：聊天内容会发送到第三方大模型服务（DeepSeek/OpenAI），切勿发送隐私或机密信息。
4. **API Key**：不要提交到 git。之前写进过 `application.yml` 的 key 建议视为已泄露，去 DeepSeek 控制台重置，并改用环境变量注入。
5. **版本兼容**：wxauto 3.9 只适配微信 3.9.8.15；微信升级/降级都可能让 UI 自动化失效。
6. **窗口要求**：微信客户端要保持打开、登录，不要最小化到系统托盘。
7. **仅本机使用**：`/api/reply` 接口没有鉴权，请勿把 8080 端口暴露到公网。

---

## 六、常见问题

- **收不到消息**：3.x 的 `GetNextNewMessage` 只能读取**未开启免打扰**的聊天；确认测试小号和新消息会话没有开免打扰。也可以在微信里给“文件传输助手”发消息测试。
- **提示版本过低/无法登录 3.9**：腾讯已拦截旧版本微信登录（“当前微信版本过低，请升级至最新版本”），
  3.x 路线已基本作废，请改用 4.x 免费轮询（`wechat_bridge_4x_free.py`）或 wxautox4。
- **发送失败**：确认微信窗口可见、未被最小化到托盘；发送频率太高时手动降低 `--interval`。
- **AI 回复“服务暂时不可用”**：检查 `$env:DEEPSEEK_API_KEY` 是否设置、Java 控制台有无 401/超时日志。
- **Java 起不来**：确认 JDK 17+、Maven 能联网拉依赖；`spring.ai.openai.api-key` 未配置时 Spring AI 可能直接报错，先按第 2 步设置环境变量。
- **旧网页版方案还能用吗**：代码保留（`wechat.bot.enabled=true` 可开启），但多数账号会被 ret=1203 拒绝，不建议继续使用。

## 免责声明

本项目仅用于技术学习。使用个人微信自动化工具存在封号风险，请自行评估并承担后果。
