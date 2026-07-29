ALTER TABLE source_registry ADD COLUMN schedule_mode VARCHAR(20) NOT NULL DEFAULT 'DAILY';
ALTER TABLE source_registry ADD COLUMN interval_hours INT NOT NULL DEFAULT 24;
ALTER TABLE source_registry ADD COLUMN schedule_timezone VARCHAR(60) NOT NULL DEFAULT 'Asia/Shanghai';
ALTER TABLE source_registry ADD COLUMN recent_days INT NOT NULL DEFAULT 7;
ALTER TABLE source_registry ADD COLUMN include_keywords VARCHAR(1000);
ALTER TABLE source_registry ADD COLUMN exclude_keywords VARCHAR(1000);
ALTER TABLE source_registry ADD COLUMN auto_save_draft BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE source_registry ADD COLUMN duplicate_strategy VARCHAR(30) NOT NULL DEFAULT 'SKIP';
ALTER TABLE source_registry ADD COLUMN max_retries INT NOT NULL DEFAULT 3;
ALTER TABLE source_registry ADD COLUMN image_usage_policy VARCHAR(40) NOT NULL DEFAULT 'MANUAL_REVIEW';
ALTER TABLE source_registry ADD COLUMN image_usage_basis VARCHAR(1000);
ALTER TABLE source_registry ADD COLUMN auto_approve_images BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE source_registry ADD COLUMN image_cache_allowed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE source_registry ADD COLUMN image_policy_reviewed_by BIGINT;
ALTER TABLE source_registry ADD COLUMN image_policy_reviewed_at TIMESTAMP NULL;

CREATE TABLE source_registry_identity (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  source_registry_id BIGINT NOT NULL,
  identity_type VARCHAR(40) NOT NULL,
  wechat_account_name VARCHAR(255),
  account_subject VARCHAR(255),
  wechat_biz VARCHAR(255),
  verification_note VARCHAR(1000),
  official_verified BOOLEAN NOT NULL DEFAULT FALSE,
  verified_by BIGINT,
  verified_at TIMESTAMP NULL,
  source_identity_fingerprint VARCHAR(64) NOT NULL UNIQUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_source_identity_registry FOREIGN KEY (source_registry_id) REFERENCES source_registry(id)
);

CREATE INDEX idx_source_identity_registry ON source_registry_identity(source_registry_id, official_verified);
CREATE INDEX idx_source_identity_wechat ON source_registry_identity(wechat_biz);
