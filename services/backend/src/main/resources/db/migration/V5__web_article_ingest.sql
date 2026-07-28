ALTER TABLE source_document ADD COLUMN original_url VARCHAR(1000);
ALTER TABLE source_document ADD COLUMN canonical_url VARCHAR(700);
ALTER TABLE source_document ADD COLUMN source_domain VARCHAR(255);
ALTER TABLE source_document ADD COLUMN source_authority_level VARCHAR(10);
ALTER TABLE source_document ADD COLUMN article_author VARCHAR(255);
ALTER TABLE source_document ADD COLUMN original_published_at TIMESTAMP NULL;
ALTER TABLE source_document ADD COLUMN crawl_time TIMESTAMP NULL;
ALTER TABLE source_document ADD COLUMN cover_image_url VARCHAR(1500);
ALTER TABLE source_document ADD COLUMN cover_image_type VARCHAR(30) NOT NULL DEFAULT 'CATEGORY_DEFAULT';
ALTER TABLE source_document ADD COLUMN image_source_name VARCHAR(255);
ALTER TABLE source_document ADD COLUMN image_source_url VARCHAR(1500);
ALTER TABLE source_document ADD COLUMN image_alt_text VARCHAR(500);
ALTER TABLE source_document ADD COLUMN image_cached BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE source_document ADD COLUMN image_license_note VARCHAR(1000);
ALTER TABLE source_document ADD COLUMN image_width INT;
ALTER TABLE source_document ADD COLUMN image_height INT;
ALTER TABLE source_document ADD COLUMN image_hash CHAR(64);
ALTER TABLE source_document ADD COLUMN image_reviewed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE source_document ADD COLUMN original_html LONGTEXT;
ALTER TABLE source_document ADD COLUMN extracted_text LONGTEXT;
ALTER TABLE source_document ADD COLUMN crawl_status VARCHAR(30);
ALTER TABLE source_document ADD COLUMN robots_status VARCHAR(30);
ALTER TABLE source_document ADD COLUMN original_page_available BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE source_document ADD COLUMN external_source_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE source_document ADD COLUMN content_kind VARCHAR(40);
ALTER TABLE source_document ADD COLUMN prompt_version VARCHAR(20);
ALTER TABLE source_document ADD COLUMN schema_version VARCHAR(20);

ALTER TABLE published_item ADD COLUMN content_kind VARCHAR(40);
ALTER TABLE published_item ADD COLUMN cover_image_url VARCHAR(1500);
ALTER TABLE published_item ADD COLUMN is_local BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE published_item ADD COLUMN reading_minutes INT NOT NULL DEFAULT 1;
ALTER TABLE published_item ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE published_item ADD COLUMN importance INT NOT NULL DEFAULT 50;

CREATE TABLE source_registry (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  domain VARCHAR(255) NOT NULL UNIQUE,
  source_name VARCHAR(255) NOT NULL,
  authority_level VARCHAR(10) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  crawl_mode VARCHAR(30) NOT NULL DEFAULT 'MANUAL',
  rate_limit INT NOT NULL DEFAULT 5,
  allow_image_cache BOOLEAN NOT NULL DEFAULT FALSE,
  requires_manual_review BOOLEAN NOT NULL DEFAULT TRUE,
  selectors_json TEXT,
  last_crawled_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE crawl_job (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  source_registry_id BIGINT NOT NULL,
  document_id BIGINT NULL,
  original_url VARCHAR(1000) NOT NULL,
  status VARCHAR(30) NOT NULL,
  next_run_at TIMESTAMP NULL,
  last_success_at TIMESTAMP NULL,
  last_error VARCHAR(1000),
  content_changed BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_web_article_canonical ON source_document(canonical_url);
CREATE INDEX idx_web_article_hash ON source_document(content_hash);
CREATE INDEX idx_web_article_kind ON source_document(content_kind);
CREATE INDEX idx_source_registry_enabled ON source_registry(enabled, authority_level);

INSERT INTO source_registry(domain,source_name,authority_level,rate_limit,allow_image_cache,requires_manual_review)
VALUES ('www.news.cn','新华网','B',5,FALSE,TRUE);
INSERT INTO source_registry(domain,source_name,authority_level,rate_limit,allow_image_cache,requires_manual_review)
VALUES ('mzj.gz.gov.cn','广州市民政局','A',5,FALSE,TRUE);
INSERT INTO source_registry(domain,source_name,authority_level,rate_limit,allow_image_cache,requires_manual_review)
VALUES ('www.shanghai.gov.cn','上海市人民政府','A',5,FALSE,TRUE);
INSERT INTO source_registry(domain,source_name,authority_level,rate_limit,allow_image_cache,requires_manual_review)
VALUES ('www.szlhq.gov.cn','深圳市龙华区人民政府','A',5,FALSE,TRUE);
