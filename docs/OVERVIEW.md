# 个人微信 AI 自动回复机器人 · 全面介绍

> 架构一句话：**Java 负责“思考”（调用远端大模型），Python 负责“收发”（操作微信 PC 客户端）**。
> 本文档覆盖：为什么这么做、三条路线怎么选、完整启动步骤、配置说明、工作原理、常见问题与风险。
> 项目方向与长期规划见 [路线图](ROADMAP.md)。

---

## 1. 项目是什么

一个运行在本地 Windows 电脑上的个人微信自动回复机器人：

- 收到微信文本消息后，调用**远端 HTTPS 大模型接口**（默认 DeepSeek，兼容 OpenAI 协议）生成回复；
- 再把回复自动发回微信；
- 可以配置只回复指定好友、是否回复群消息、跳过某些会话。

技术栈：

| 端 | 技术 |
| --- | --- |
| Java 逻辑端 | JDK 17+、Spring Boot 3.5、Spring AI 1.1（`spring-ai-starter-model-openai`） |
| Python 微信端 | Python 3.9~3.12、wxauto4（免费）/ wxautox4（付费） |
| 大模型 | DeepSeek / OpenAI / 任意 OpenAI 兼容的远端 HTTPS 接口（严禁本地模型） |

---

## 2. 为什么是这个架构

### 2.1 网页版协议已经死了

最初的方案是 wechat-api（网页版协议，注解驱动）。但微信服务器现在对绝大多数账号返回：

```
<error><ret>1203</ret><message>Unable to log in</message></error>
```

1203 表示“该微信号不能登录网页微信”，属于**账号级限制**，客户端代码无法绕过。这个项目里保留的
`io/uouo/wechat`（wechat-api-uouo，UOS 协议 fork）源码就是为此准备的，默认已关闭
（`wechat.bot.enabled=false`）。

### 2.2 3.x 客户端登录也被封了

微信在 2026 年起在服务端拦截旧版本登录，3.9.x 客户端扫码时直接提示：

```
当前微信版本过低，请升级至最新版本。
```

所以 wxauto 3.9 免费路线（依赖微信 3.9.8.15）也已经不可用。

### 2.3 只剩 UI 自动化这一条本地路

剩下的可行方案是模拟人工操作微信 PC 客户端（UI 自动化）。wxauto 系列就是干这个的：

- **wxauto4（免费版）**：适配微信 4.x（上限 4.1.8.107），但**没有监听接口**，只能查询/发送；
- **wxautox4（Plus 付费版）**：适配微信 4.x，有完整的 `GetNextNewMessage` / `AddListenChat` 监听接口，需要激活码；
- **wxauto（3.9 免费版）**：只适配微信 3.9.8.15，登录已基本被封，不推荐。

---

## 3. 三条路线对比

| 方案 | 微信版本 | Python 库 | 费用 | 状态 |
| --- | --- | --- | --- | --- |
| 3.x 路线 | 3.9.8.15 | `wxauto`（已 vendored） | 免费 | ❌ 腾讯拦截旧版本登录，基本作废 |
| **4.x 免费路线（推荐）** | 4.x（≤4.1.8.107） | `wxauto4` | 免费 | ✅ 预览轮询模式，稳定可用 |
| 4.x Plus 路线 | 4.x | `wxautox4` | 需激活码 | ✅ 监听式，体验最好 |

> 注意：微信 4.x 会自动更新，一旦超过 wxauto4 支持的上限（4.1.8.107），免费路线会失效。
> 要么在更新后降级微信，要么改用 wxautox4（它跟进新版更快）。

---

## 4. 整体架构

```mermaid
flowchart LR
    A[微信 PC 客户端] -->|wxauto4 轮询会话列表| B[Python 桥接脚本<br/>wechat_bridge_4x_free.py]
    B -->|POST /api/reply| C[Java 逻辑端<br/>Spring Boot 3 + Spring AI]
    C -->|HTTPS 调用| D[DeepSeek / OpenAI<br/>远端大模型]
    D -->|回复文本| C
    C -->|{"reply": "..."}| B
    B -->|wx.SendMsg 发送| A
```

消息流转时序：

```text
1. 好友给测试小号发文本消息
2. Python 每 2 秒轮询一次微信会话列表（GetSession）
3. 发现某会话的“最新消息预览”变化（且有未读角标）
4. 把 {sender: 会话名, content: 预览文本} POST 给 Java 的 /api/reply
5. Java 用 Spring AI 调 DeepSeek 生成回复，返回 {"reply": "..."}
6. Python 用 wx.SendMsg(reply, who=会话名) 发回微信
7. Java 同时把“收到/发送”记入 logs/messages.txt
```

---

## 5. 目录结构

```text
Spring-AI/
├── pom.xml                          # Maven 依赖
├── README.md                        # 快速上手
├── docs/OVERVIEW.md                 # 本文档
├── src/main/resources/application.yml
├── src/main/java/com/example/wechataibot/
│   ├── WechatAiApplication.java     # Spring Boot 启动类
│   ├── web/WechatBridgeController.java   # 核心桥接接口 POST /api/reply
│   ├── web/MessageTraceLogger.java  # 收发消息流水日志
│   ├── bot/                          # 旧网页版方案（默认关闭）
│   └── config/WeChatBotProperties.java
├── python/
│   ├── wechat_bridge_4x_free.py     # 4.x 免费路线（推荐，预览轮询）
│   ├── wechat_bridge_4x.py          # 4.x Plus 路线（wxautox4 监听）
│   ├── wechat_bridge_3x.py          # 3.x 路线（已基本作废）
│   ├── bot_config.py                # 回复范围配置加载
│   ├── config.ini                   # 回复范围配置
│   ├── requirements.txt
│   ├── diagnose_wxauto4.py          # 诊断脚本
│   └── wxauto/                      # vendored 的 wxauto 3.9（保留）
```

---

## 6. 快速开始

### 6.1 准备

- JDK 17+、Maven 3.8+、Python 3.9~3.12（仅 Windows 10/11）
- 微信 PC 客户端（4.x，≤4.1.8.107）
- 一个【测试小号】和一个 DeepSeek（或 OpenAI）API Key

### 6.2 配置 API Key（环境变量，不要写进 yml）

```powershell
$env:DEEPSEEK_API_KEY = "sk-你的key"
```

### 6.3 启动 Java 逻辑端

```powershell
cd C:\path\to\Spring-AI
mvn spring-boot:run
```

看到 “Tomcat started on port 8080” 后验证：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/health
# 期望输出 status = ok
```

### 6.4 安装 Python 依赖

```powershell
cd python
pip install -r requirements.txt
```

### 6.5 启动 Python 桥接端（预览轮询，默认）

```powershell
cd C:\path\to\Spring-AI\python
python wechat_bridge_4x_free.py --debug
```

看到 `[INFO] 开始预览轮询` 后，用另一个号给测试小号发条文本消息，即可收到 AI 回复。

> 微信主窗口要**保持可见、停在聊天页**，不要最小化到托盘，也不要有设置/登录窗口挡着。

---

## 7. 配置详解

### 7.1 Java 端 application.yml

| 配置项 | 示例值 | 说明 |
| --- | --- | --- |
| `spring.ai.openai.base-url` | `https://api.deepseek.com` | 远端 HTTPS 接口，严禁本地 Ollama |
| `spring.ai.openai.api-key` | `${DEEPSEEK_API_KEY:sk-REPLACE_ME}` | API Key，推荐环境变量注入 |
| `spring.ai.openai.chat.options.model` | `deepseek-chat` | 模型名（也可用 `deepseek-reasoner`） |
| `spring.ai.openai.chat.options.temperature` | `0.7` | 回答随机性（0~2） |
| `server.port` | `8080` | Java 服务端口 |
| `logging.file.name` | `logs/wechat-ai-bot.txt` | 完整运行日志 |
| `wechat.bot.enabled` | `false` | 旧网页版方案开关，保持 false |

### 7.2 Python 端 config.ini

```ini
[reply]
friends = 姐姐, 张三      # 只回复这些好友；留空 = 回复所有私聊
groups = false            # true = 回复群消息；false = 不回复（默认）
group_names = 工作群       # 开启 groups 后，只回复这些群；留空 = 回复所有群
skip_chats = 文件传输助手   # 完全跳过这些会话（不读取也不回复）
```

名字要和微信里显示的一模一样（昵称或备注名）。命令行参数优先级更高：

```powershell
python wechat_bridge_4x_free.py --reply-friends 姐姐,张三 --reply-groups --group-names 工作群 --skip-chats 文件传输助手
```

### 7.3 桥接脚本常用参数

| 参数 | 说明 |
| --- | --- |
| `--debug` | 打印每轮检查的会话、处理的消息 |
| `--interval 2` | 轮询间隔（秒） |
| `--reply-groups` | 开启群消息回复 |
| `--reply-friends 姐姐,张三` | 只回复这些好友 |
| `--group-names 工作群` | 群白名单 |
| `--skip-chats 文件传输助手` | 跳过指定会话 |
| `--reply-unread` | 启动时也回复未读消息（最多最近 5 条） |
| `--read-messages` | 旧模式：打开聊天窗口读完整消息（不推荐，可能卡死） |
| `--scan-all` | 旧模式下的全量扫描 |
| `--ping` | 只检查 Java 服务健康 |

### 7.4 Obsidian 知识库问答（RAG）

配置 `obsidian.vault-path`（或环境变量 `OBSIDIAN_VAULT_PATH`）后，Java 启动时自动扫描
Markdown 库，按标题/段落切块，提问时用轻量 BM25 关键词检索命中 top-k 笔记块拼进 Prompt，
让 AI 基于你自己的笔记回答。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `obsidian.vault-path` | 空（不启用） | Obsidian 库根目录 |
| `obsidian.top-k` | 5 | 每次提问拼入的笔记块数 |
| `obsidian.max-chunk-chars` | 600 | 单块最大字符数 |
| `obsidian.exclude-patterns` | `.mimocode` / `node_modules` / `.obsidian` / `40-Life` | 相对路径包含即排除 |

调试接口：`GET /api/rag/status`（索引状态）、`POST /api/rag/refresh`（重建索引）、
`GET /api/rag/search?q=...`（测试检索）。私人聊天目录（`40-Life`）默认不索引，避免隐私外泄。

---

## 8. 工作原理：预览轮询模式

免费版 wxauto4 没有监听接口，且它的 `GetAllMessage`（读完整消息列表）在部分消息上会
**“解析发送者”无限重试**，导致整个脚本卡死、请求发不到后端。

因此默认采用**预览轮询**：

1. 每轮调用 `wx.GetSession()` 拿会话列表（每个会话自带最后一条消息预览）；
2. 对比上一轮的预览哈希，发现变化 + 有未读角标（或该会话当前正打开）→ 视为新消息；
3. 直接把预览文本 POST 给 Java，拿到回复后用 `wx.SendMsg` 发回；
4. 防自回自答：自己发出去的回复会成为新的预览，通过“未读角标 + 上次回复回显”两个条件过滤掉；
5. 启动首轮只建立基线，不回复历史消息（可用 `--reply-unread` 改为也回复最近未读）。

已知取舍：

- 拿不到发送者姓名（私聊发送者 = 会话名，够用）；
- 无法可靠区分群聊，群消息会按私聊处理——**不想回群就用 `skip_chats` 或白名单排除**；
- 每个会话只看最后一条消息（连续多条只回最新一条）。

### 对话记忆（多轮上下文）

Java 端使用 Spring AI 官方的 `ChatMemory` 接口实现多轮记忆：

- 每个微信聊天对象 = 一个会话（conversationId = 发送者名）；
- 记忆用 `MessageWindowChatMemory` 滑动窗口实现，**每个会话保留最近 20 条消息**（约 10 轮），
  每次请求先取历史拼进 Prompt，再把“用户消息 + AI 回复”写回记忆，同时控制 token 成本；
- 发送 **“清空记忆”** 会清空当前会话的上下文并回复确认；
- 记忆保存在 JVM 内存中，**重启 Java 服务后会清空**；如需重启后仍保留，
  可把 `ChatMemoryRepository` 换成 JDBC/文件实现（见 `MemoryConfig` 注释）。

---

## 9. 日志

| 文件 | 内容 |
| --- | --- |
| `logs/wechat-ai-bot.txt` | Java 完整运行日志（启动、请求、AI 调用、错误） |
| `logs/messages.txt` | 仅收发消息流水：`时间 | 收到/发送 | 来自/发给 | 内容` |

Python 端日志实时打印在控制台（已开启行缓冲，重定向到文件也能实时写入）。

---

## 10. 常见问题排查（走过的坑）

| 现象 | 原因与解决 |
| --- | --- |
| 启动卡在“正在连接微信客户端” | 微信有设置/登录/升级窗口挡着，或主窗口最小化到托盘。关掉多余窗口、保持聊天页可见后重试 |
| wxauto4 初始化报 Find Control Timeout | 微信版本超过 4.1.8.107（自动更新了），需降级微信或改用 wxautox4 |
| 微信提示“版本过低” | 3.x 路线已被腾讯封杀，改用 4.x 路线 |
| 消息到不了后端 / 一直“解析消息…重试” | 旧模式读消息卡死，改用默认的预览轮询模式 |
| 收不到消息 | 会话开了免打扰；微信窗口不可见；发送者不在 `friends` 白名单 |
| 群消息被回复 | 预览模式无法区分群聊，把群名加入 `skip_chats` 或用白名单 |
| Java 起不来 / 8080 被占用 | 有残留的 Java 测试进程占着端口，结束占用进程后重启 |
| AI 回复“服务暂时不可用” | `DEEPSEEK_API_KEY` 未配置或 key 无效，检查 Java 控制台日志 |
| Python 终端没有日志 | 已加行缓冲修复；重启脚本生效 |

---

## 11. 风险提示（务必阅读）

1. **账号风险**：个人微信自动化违反《微信软件许可及服务协议》，可能被限制登录、封号。**请务必使用测试小号，不要用主号。**
2. **风控**：UI 自动化模拟人工操作，高频收发容易触发风控。默认间隔 2 秒、每次发送后停顿 1 秒，请勿调快。
3. **隐私**：聊天内容会发送到第三方大模型服务（DeepSeek/OpenAI），切勿发送隐私或机密信息。
4. **API Key**：不要提交到 git；历史上写进过配置文件的 key 建议视为已泄露并重置。
5. **版本脆弱性**：微信客户端升级可能让 wxauto 系列失效，请固定微信版本或使用付费版。
6. **仅本机使用**：`/api/reply` 无鉴权，请勿把 8080 端口暴露到公网。

## 免责声明

本项目仅用于技术学习。使用个人微信自动化工具存在封号风险，请自行评估并承担后果。
