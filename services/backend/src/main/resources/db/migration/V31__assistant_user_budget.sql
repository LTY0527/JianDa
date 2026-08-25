-- Phase 9.8_3: Assistant 三级预算与用户维度字段
-- 增加 resident_user_id 和 visitor_id 用于区分居民/访客预算统计
-- 使用独立 ALTER 语句以兼容 H2（MODE=MySQL 不支持多列 ADD COLUMN 逗号语法）
ALTER TABLE assistant_query_event ADD COLUMN resident_user_id BIGINT NULL;
ALTER TABLE assistant_query_event ADD COLUMN visitor_id VARCHAR(64) NULL;

CREATE INDEX idx_assistant_query_resident ON assistant_query_event(resident_user_id, created_at);
CREATE INDEX idx_assistant_query_visitor ON assistant_query_event(visitor_id, created_at);
