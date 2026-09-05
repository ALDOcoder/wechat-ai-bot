-- ============================================================================
-- 迁移 v1.2：message_log 增加 provider 列（记录 AI 回复用的模型，便于历史标注）
-- 已存在该列的库执行会报 Duplicate column，忽略即可。
-- ============================================================================

ALTER TABLE message_log
  ADD COLUMN provider VARCHAR(20) NOT NULL DEFAULT '' AFTER msg_type;
