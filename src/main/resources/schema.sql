-- wechat-ai-bot 表结构（应用启动时自动执行，CREATE TABLE IF NOT EXISTS 幂等）

-- AI 对话记忆：一个会话一行，messages_json 存最近窗口内的消息列表
CREATE TABLE IF NOT EXISTS chat_memory (
  conversation_id VARCHAR(100) NOT NULL PRIMARY KEY,
  messages_json   MEDIUMTEXT  NOT NULL,
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 全量消息流水：收到/发送各一条，按维度字段区分
CREATE TABLE IF NOT EXISTS message_log (
  id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  conversation_id VARCHAR(100) NOT NULL,   -- 私聊=好友名 / 群聊=group:群名:发送者 / web=web:IP
  scene           VARCHAR(10)  NOT NULL,   -- friend / group / web
  direction       VARCHAR(10)  NOT NULL,   -- RECEIVED / SENT
  sender          VARCHAR(100) NOT NULL DEFAULT '',
  client_ip       VARCHAR(45)  NOT NULL DEFAULT '',
  msg_type        VARCHAR(20)  NOT NULL DEFAULT 'text',
  provider        VARCHAR(20)  NOT NULL DEFAULT '',  -- 本次回复使用的模型：zhipu/deepseek（收到的消息为空）
  content         MEDIUMTEXT   NOT NULL,
  use_rag         TINYINT(1)   NOT NULL DEFAULT 0,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_conv_time (conversation_id, created_at),
  KEY idx_scene_time (scene, created_at),
  KEY idx_ip_time (client_ip, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 滚动摘要：每个会话一份，覆盖"会话开头 ~ 窗口边界"的旧对话要点
-- last_message_id 是游标 = 摘要已覆盖到 message_log.id 的位置（该条之前都已进摘要）
CREATE TABLE IF NOT EXISTS conversation_summary (
  conversation_id VARCHAR(100) NOT NULL PRIMARY KEY,
  summary         TEXT         NOT NULL,
  last_message_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- RAG 排除规则数据字典：一行一条规则，前端经 /api/rag/patterns 增删改（改后自动重建索引）
-- pattern = 相对路径子串匹配（忽略大小写）；enabled=0 表示停用但保留记录
-- 生效语义 = application.yml 的 exclude-patterns 基线 ∪ 本表 enabled=1 的规则（并集）
CREATE TABLE IF NOT EXISTS rag_exclude_pattern (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  pattern    VARCHAR(200) NOT NULL,
  remark     VARCHAR(200) NOT NULL DEFAULT '',
  enabled    TINYINT(1)   NOT NULL DEFAULT 1,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_pattern (pattern)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 会话设置：每个会话一行的“权限 + 偏好”
-- preferred_model 记录该会话默认用哪个模型（zhipu=免费 / deepseek=付费）
-- allow_deepseek  是否允许该会话切换到付费模型（默认 0 = 全禁，网页后台动态开启）
CREATE TABLE IF NOT EXISTS conversation_setting (
  conversation_id VARCHAR(100) NOT NULL PRIMARY KEY,
  scene           VARCHAR(10)  NOT NULL,   -- friend / group / web
  preferred_model VARCHAR(20)  NOT NULL DEFAULT 'zhipu',
  allow_deepseek  TINYINT(1)   NOT NULL DEFAULT 0,
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
