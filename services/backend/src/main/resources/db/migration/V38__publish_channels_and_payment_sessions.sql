ALTER TABLE source_document ADD COLUMN publish_channel VARCHAR(24);
ALTER TABLE source_document ADD COLUMN suggested_publish_channel VARCHAR(24);
ALTER TABLE source_document ADD COLUMN channel_confidence DECIMAL(5,4);
ALTER TABLE source_document ADD COLUMN channel_reason VARCHAR(500);

ALTER TABLE published_item ADD COLUMN publish_channel VARCHAR(24) NOT NULL DEFAULT 'COMMUNITY';
ALTER TABLE published_item ADD COLUMN promote_to_recommend BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE published_item ADD COLUMN importance_level VARCHAR(16) NOT NULL DEFAULT 'NORMAL';

UPDATE published_item SET publish_channel = CASE
  WHEN category LIKE '%健康%' OR content_kind='HEALTH_EDUCATION' THEN 'HEALTH'
  WHEN category LIKE '%养老%' OR content_kind='ELDERLY_POLICY' THEN 'ELDERLY'
  WHEN category LIKE '%助餐%' OR title LIKE '%食堂%' OR title LIKE '%助餐%' THEN 'MEALS'
  WHEN category LIKE '%反诈%' OR content_kind='ANTI_FRAUD' THEN 'FRAUD'
  WHEN category LIKE '%活动%' OR title LIKE '%活动%' OR title LIKE '%报名%' THEN 'ACTIVITY'
  WHEN category LIKE '%办事%' OR content_kind='SERVICE_NOTICE' THEN 'SERVICES'
  ELSE 'COMMUNITY'
END;

UPDATE source_document SET
  publish_channel=(SELECT p.publish_channel FROM published_item p WHERE p.document_id=source_document.id),
  suggested_publish_channel=(SELECT p.publish_channel FROM published_item p WHERE p.document_id=source_document.id),
  channel_confidence=0.7000,
  channel_reason='由既有分类和内容类型迁移，发布时可人工调整'
WHERE EXISTS (SELECT 1 FROM published_item p WHERE p.document_id=source_document.id);

CREATE INDEX idx_published_channel ON published_item(status,publish_channel,published_at);

CREATE TABLE membership_payment_session (
  id VARCHAR(80) PRIMARY KEY,
  resident_user_id BIGINT NOT NULL,
  membership_plan_id BIGINT NOT NULL,
  provider VARCHAR(30) NOT NULL,
  payment_method VARCHAR(20) NOT NULL,
  amount_cents BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL,
  qr_payload VARCHAR(1000),
  expires_at TIMESTAMP NOT NULL,
  paid_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_membership_payment_resident ON membership_payment_session(resident_user_id,created_at);
CREATE INDEX idx_membership_payment_status ON membership_payment_session(status,expires_at);
