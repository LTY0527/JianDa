CREATE TABLE resident_reminder (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  anonymous_user_id VARCHAR(80) NOT NULL,
  published_item_id BIGINT NOT NULL,
  reminder_type VARCHAR(30) NOT NULL,
  remind_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_reminder_item FOREIGN KEY (published_item_id) REFERENCES published_item(id),
  CONSTRAINT uk_resident_reminder UNIQUE (anonymous_user_id, published_item_id, reminder_type)
);

CREATE INDEX idx_reminder_user_time ON resident_reminder(anonymous_user_id, remind_at);

CREATE TABLE usage_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NULL,
  anonymous_session_id VARCHAR(80) NULL,
  content_id BIGINT NULL,
  event_type VARCHAR(40) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_usage_event_created ON usage_event(created_at, event_type);
CREATE INDEX idx_usage_event_content ON usage_event(content_id, event_type);
