CREATE TABLE ai_processing_queue (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  source_registry_id BIGINT NULL,
  crawl_job_id BIGINT NULL,
  crawl_task_root_id BIGINT NULL,
  document_id BIGINT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  status VARCHAR(30) NOT NULL,
  reason_code VARCHAR(60),
  reason_summary VARCHAR(500),
  estimated_tokens INT NOT NULL DEFAULT 0,
  actual_tokens INT NOT NULL DEFAULT 0,
  approved_by BIGINT NULL,
  approved_at TIMESTAMP NULL,
  available_at TIMESTAMP NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  provider_id VARCHAR(80),
  model_id VARCHAR(120),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_ai_queue_content_hash ON ai_processing_queue(content_hash);
CREATE INDEX idx_ai_queue_status_available ON ai_processing_queue(status,available_at,created_at);
CREATE INDEX idx_ai_queue_document ON ai_processing_queue(document_id,created_at);

CREATE TABLE ai_budget_usage (
  budget_date DATE NOT NULL,
  scope_type VARCHAR(20) NOT NULL,
  scope_id BIGINT NOT NULL,
  reserved_articles INT NOT NULL DEFAULT 0,
  settled_articles INT NOT NULL DEFAULT 0,
  reserved_tokens BIGINT NOT NULL DEFAULT 0,
  actual_tokens BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (budget_date,scope_type,scope_id)
);

CREATE TABLE ai_budget_reservation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  queue_id BIGINT NULL,
  processing_job_id BIGINT NULL,
  source_registry_id BIGINT NULL,
  crawl_task_root_id BIGINT NULL,
  document_id BIGINT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  budget_date DATE NOT NULL,
  estimated_tokens INT NOT NULL,
  actual_tokens INT NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL,
  execution_started BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  settled_at TIMESTAMP NULL
);

CREATE INDEX idx_ai_reservation_document ON ai_budget_reservation(document_id,status);
CREATE INDEX idx_ai_reservation_task ON ai_budget_reservation(crawl_task_root_id,status);
CREATE INDEX idx_ai_reservation_hash_status ON ai_budget_reservation(content_hash,status);

CREATE TABLE ai_execution_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  source_registry_id BIGINT NULL,
  crawl_job_id BIGINT NULL,
  document_id BIGINT NOT NULL,
  queue_id BIGINT NULL,
  reason_code VARCHAR(60),
  budget_type VARCHAR(40),
  estimated_tokens INT NOT NULL DEFAULT 0,
  actual_tokens INT NOT NULL DEFAULT 0,
  approved_execution BOOLEAN NOT NULL DEFAULT FALSE,
  executed BOOLEAN NOT NULL DEFAULT FALSE,
  provider_id VARCHAR(80),
  model_id VARCHAR(120),
  result VARCHAR(30) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ai_audit_document ON ai_execution_audit(document_id,created_at);
