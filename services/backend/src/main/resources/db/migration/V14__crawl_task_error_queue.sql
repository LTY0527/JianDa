ALTER TABLE crawl_job ADD COLUMN parent_job_id BIGINT NULL;
ALTER TABLE crawl_job ADD COLUMN trigger_type VARCHAR(30) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE crawl_job ADD COLUMN processing_stage VARCHAR(40) NOT NULL DEFAULT 'DISCOVERY';
ALTER TABLE crawl_job ADD COLUMN discovery_method VARCHAR(30);
ALTER TABLE crawl_job ADD COLUMN discovery_page VARCHAR(1000);
ALTER TABLE crawl_job ADD COLUMN discovered_count INT NOT NULL DEFAULT 0;
ALTER TABLE crawl_job ADD COLUMN added_count INT NOT NULL DEFAULT 0;
ALTER TABLE crawl_job ADD COLUMN duplicate_count INT NOT NULL DEFAULT 0;
ALTER TABLE crawl_job ADD COLUMN skipped_count INT NOT NULL DEFAULT 0;
ALTER TABLE crawl_job ADD COLUMN failed_count INT NOT NULL DEFAULT 0;
ALTER TABLE crawl_job ADD COLUMN created_by BIGINT NULL;
ALTER TABLE crawl_job ADD COLUMN scheduler_identity VARCHAR(100);

CREATE TABLE crawl_job_error (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  crawl_job_id BIGINT NOT NULL,
  source_registry_id BIGINT NOT NULL,
  failed_url VARCHAR(1000),
  processing_stage VARCHAR(40) NOT NULL,
  error_code VARCHAR(80) NOT NULL,
  error_summary VARCHAR(500) NOT NULL,
  retryable BOOLEAN NOT NULL DEFAULT FALSE,
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMP NULL,
  resolved_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_crawl_job_parent ON crawl_job(parent_job_id, status);
CREATE INDEX idx_crawl_job_filter ON crawl_job(source_registry_id, status, created_at);
CREATE INDEX idx_crawl_error_job ON crawl_job_error(crawl_job_id, resolved_at, created_at);
CREATE INDEX idx_crawl_error_retry ON crawl_job_error(retryable, next_retry_at, retry_count);
