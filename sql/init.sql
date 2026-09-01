-- ============================================================================
-- wechat-ai-bot · MySQL 初始化脚本（只需手动执行一次）
-- 用法（PowerShell，使用有管理权限的账号，例如 root）：
--   mysql -u root -p < sql/init.sql
--
-- ⚠️ 执行前请把下方 '改成你的密码' 换成你自己的密码，并在环境变量
--    MYSQL_USER / MYSQL_PASSWORD 中配置同样的值（不要提交到 git）。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS wechat_bot
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'bot'@'localhost' IDENTIFIED BY '改成你的密码';
CREATE USER IF NOT EXISTS 'bot'@'127.0.0.1' IDENTIFIED BY '改成你的密码';

GRANT ALL PRIVILEGES ON wechat_bot.* TO 'bot'@'localhost';
GRANT ALL PRIVILEGES ON wechat_bot.* TO 'bot'@'127.0.0.1';
FLUSH PRIVILEGES;
