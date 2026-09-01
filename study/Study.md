## 这个文档是我自己学习所记录的


1.  ### 什么是rag = retrieval-Augmented Generation(索引增强生成)
    ##### 大模型的训练数据有截止日期，也不知道你私人的信息。RAG 的流程是：
    1. ``检索（Retrieval）：根据用户提问，从外部知识库中找出相关的片段 ``
    2. ``增强（Augmented）：把检索到的内容拼进 Prompt 里当上下文``
    3. ``生成（Generation）：大模型基于这些"开卷资料"生成回答``
    #### 好处：让大模型能回答"你不知道的、私有的、最新的"问题，同时减少胡说八道。``
   2.  ### 在这个 Spring AI 项目中 RAG 是怎么体现的？
       ##### 这个项目实现的是一个基于 Obsidian 笔记库的关键词检索 RAG，完整流程如下：
       1. 索引阶段（启动时）
         - VaultIndexService.java — 启动时扫描你的 Obsidian Markdown 库：
         -  遍历 vault 目录下所有 .md 文件
         -  按 Markdown 标题（# ~ ######） 切块，每块最大 600 字符
         -  清理掉 frontmatter、双链语法、图片等 Markdown 符号
         - 切块后存进内存 List<NoteChunk>（每条包含：文件路径、章节标题、正文）
       2. 检索阶段（提问时）
       #### KeywordSearchService.java — 轻量级 BM25 关键词检索：
       - 切词：英文按单词，中文按二元组（如"金蝶苍穹" → 金蝶/蝶苍/苍穹）
       - BM25 打分：统计每个词的 TF（词频）× IDF（逆文档频率），按长度归一化
       - 返回 top-5 最相关的笔记块
       3. 拼进 Prompt（核心桥接）
        #### WechatBridgeController.java:126-142 — 在调大模型之前：
       ```
       boolean useRag = request.useRag() != null && request.useRag();
       if (useRag && obsidianProperties.isEnabled()) {
           var hits = searchService.search(text, obsidianProperties.getTopK());
           // 把检索结果拼成 SystemMessage 注入 Prompt
           messages.add(new SystemMessage("请优先参考以下本地笔记内容回答..."));
       }
        ```
       4. 角色控制（谁能用 RAG）
       ```
       config.ini 中 rag_friends 白名单决定谁能触发知识库检索，Python 端据此设置 useRag=true。普通聊天者不会触发 RAG，保护隐私和 token。
        整体数据流
        用户在微信提问 "金蝶苍穹怎么用？"
            ↓
        Python 发现该用户在 rag_friends 白名单 → useRag=true
            ↓
        Java 端 BM25 检索 → 命中 5 个笔记块
            ↓
        拼成 SystemMessage: "请优先参考以下本地笔记..."
            ↓
        连同系统提示 + 历史记忆 + 用户问题 → 发给 DeepSeek
            ↓
        DeepSeek 基于笔记内容回答 → 返回给用户
        总结：这是一个"小而美"的 RAG 实现——零外部依赖（不用向量数据库），纯 Java 内存索引 + BM25 关键词检索，作为 SystemMessage 注入 Spring AI 的 Prompt 中，让大模型基于你自己的 Obsidian 笔记回答问题。
       ```



```text
┌──────────────┐                          ┌──────────────────┐
│   你的 Java   │   ① 发送：问题 + 可用工具清单  │   DeepSeek LLM   │
│   服务       │ ──────────────────────────→  │                  │
│              │    "你有这些工具可用：           │  看到工具描述后    │
│              │     - searchNotes(query)      │  决定要用哪个      │
│              │     - getCurrentTime()        │  输出结构化请求     │
│              │                              │                  │
│              │   ② 返回：模型说"我要调工具"     │                  │
│              │ ←──────────────────────────  │  不是文字回答，     │
│              │    tool_call:                 │  而是结构化指令：   │
│              │    name=searchNotes           │  "调searchNotes,  │
│              │    args={query:"金蝶"}         │   参数是金蝶"      │
│              │                              │                  │
│  ③ Java 自己  │                              │                  │
│  执行方法     │   searchNotes("金蝶")         │   模型在等你      │
│  拿到结果     │   → "金蝶苍穹部署需要..."       │   回传结果        │
│              │                              │                  │
│              │   ③ 把结果回传给模型            │                  │
│              │ ──────────────────────────→  │  模型拿到真实数据   │
│              │                              │  生成最终回答      │
│              │   ④ 返回最终文字回答            │                  │
│              │ ←──────────────────────────  │  "金蝶苍穹需要先    │
│              │    "金蝶苍穹需要先安装JDK"       │   安装JDK..."   
```