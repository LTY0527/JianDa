ALTER TABLE source_registry ADD COLUMN discovery_mode VARCHAR(30) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE source_registry ADD COLUMN include_patterns VARCHAR(1000);
ALTER TABLE source_registry ADD COLUMN exclude_patterns VARCHAR(1000);
ALTER TABLE source_registry ADD COLUMN max_pages INT NOT NULL DEFAULT 1;
ALTER TABLE source_registry ADD COLUMN newest_first BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE source_registry ADD COLUMN published_date_selector VARCHAR(500);
ALTER TABLE source_registry ADD COLUMN daily_crawl_time VARCHAR(8) NOT NULL DEFAULT '03:30';
ALTER TABLE source_registry ADD COLUMN max_articles_per_run INT NOT NULL DEFAULT 5;
ALTER TABLE source_registry ADD COLUMN request_interval_ms INT NOT NULL DEFAULT 1000;
ALTER TABLE source_registry ADD COLUMN allow_image_candidates BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE source_registry ADD COLUMN failure_count INT NOT NULL DEFAULT 0;
ALTER TABLE source_registry ADD COLUMN paused_at TIMESTAMP NULL;
ALTER TABLE source_registry ADD COLUMN next_run_at TIMESTAMP NULL;
ALTER TABLE source_registry ADD COLUMN organization_id BIGINT NULL;
ALTER TABLE source_registry ADD COLUMN operator_id BIGINT NULL;

ALTER TABLE crawl_job ADD COLUMN discovered_at TIMESTAMP NULL;
ALTER TABLE crawl_job ADD COLUMN started_at TIMESTAMP NULL;
ALTER TABLE crawl_job ADD COLUMN finished_at TIMESTAMP NULL;
ALTER TABLE crawl_job ADD COLUMN duration_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE crawl_job ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE crawl_job ADD COLUMN error_type VARCHAR(40);
ALTER TABLE crawl_job ADD COLUMN canonical_url VARCHAR(700);
ALTER TABLE crawl_job ADD COLUMN cache_hit BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE crawl_job ADD COLUMN lock_owner VARCHAR(100);
ALTER TABLE crawl_job ADD COLUMN lock_until TIMESTAMP NULL;

CREATE TABLE image_candidate (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  candidate_url VARCHAR(1500) NOT NULL,
  source_page_url VARCHAR(1500) NOT NULL,
  source_name VARCHAR(255),
  alt_text VARCHAR(500),
  width INT,
  height INT,
  image_hash CHAR(64),
  discovery_method VARCHAR(30) NOT NULL,
  rights_status VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
  review_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
  rejection_reason VARCHAR(1000),
  usage_basis VARCHAR(1000),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reviewed_at TIMESTAMP NULL,
  reviewer_id BIGINT NULL
);

CREATE UNIQUE INDEX uk_crawl_job_registry_url ON crawl_job(source_registry_id, canonical_url);
CREATE INDEX idx_crawl_job_status_next ON crawl_job(status, next_run_at);
CREATE INDEX idx_source_registry_schedule ON source_registry(enabled, next_run_at);
CREATE INDEX idx_image_candidate_document ON image_candidate(document_id, review_status);
