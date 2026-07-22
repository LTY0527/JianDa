CREATE TABLE organization (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(120) NOT NULL,
  code VARCHAR(40) NOT NULL UNIQUE,
  type VARCHAR(40) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE staff_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  organization_id BIGINT NOT NULL,
  username VARCHAR(60) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  display_name VARCHAR(60) NOT NULL,
  role VARCHAR(30) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_staff_org FOREIGN KEY (organization_id) REFERENCES organization(id)
);

CREATE TABLE content_source (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  organization_id BIGINT NULL,
  source_type VARCHAR(30) NOT NULL,
  source_name VARCHAR(160) NOT NULL,
  source_url VARCHAR(500),
  publisher VARCHAR(160),
  published_at TIMESTAMP NULL,
  imported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE source_document (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  organization_id BIGINT NOT NULL,
  content_source_id BIGINT NULL,
  title VARCHAR(200) NOT NULL,
  file_name VARCHAR(255),
  file_type VARCHAR(30),
  storage_path VARCHAR(500),
  raw_text TEXT,
  page_count INT NOT NULL DEFAULT 0,
  processing_status VARCHAR(30) NOT NULL DEFAULT 'UPLOADED',
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE document_segment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  page_no INT NOT NULL,
  segment_no INT NOT NULL,
  text TEXT NOT NULL,
  start_offset INT NOT NULL DEFAULT 0,
  end_offset INT NOT NULL DEFAULT 0
);

CREATE TABLE processing_job (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  job_type VARCHAR(30) NOT NULL,
  status VARCHAR(30) NOT NULL,
  progress INT NOT NULL DEFAULT 0,
  error_message VARCHAR(1000),
  started_at TIMESTAMP NULL,
  finished_at TIMESTAMP NULL
);

CREATE TABLE extracted_field (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  field_type VARCHAR(40) NOT NULL,
  field_label VARCHAR(60) NOT NULL,
  field_value TEXT NOT NULL,
  page_no INT NOT NULL,
  segment_id BIGINT NULL,
  source_quote TEXT NOT NULL,
  confidence DECIMAL(5,4) NOT NULL,
  review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  reviewer_id BIGINT NULL,
  reviewed_at TIMESTAMP NULL
);

CREATE TABLE generated_content (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  content_type VARCHAR(40) NOT NULL,
  title VARCHAR(200),
  content_json TEXT,
  plain_text TEXT,
  version INT NOT NULL DEFAULT 1,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE review_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  reviewer_id BIGINT NOT NULL,
  action VARCHAR(30) NOT NULL,
  comment VARCHAR(1000),
  before_snapshot TEXT,
  after_snapshot TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE published_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  slug VARCHAR(180) NOT NULL UNIQUE,
  title VARCHAR(200) NOT NULL,
  summary TEXT NOT NULL,
  category VARCHAR(40) NOT NULL,
  cover_type VARCHAR(30) NOT NULL DEFAULT 'PLAIN',
  published_by BIGINT NOT NULL,
  published_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
  source_name VARCHAR(160) NOT NULL,
  source_url VARCHAR(500)
);

CREATE TABLE user_preference (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  anonymous_user_id VARCHAR(80) NOT NULL UNIQUE,
  font_size INT NOT NULL DEFAULT 18,
  speech_rate DECIMAL(3,2) NOT NULL DEFAULT 0.90,
  high_contrast BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE favorite (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  anonymous_user_id VARCHAR(80) NOT NULL,
  published_item_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_favorite UNIQUE (anonymous_user_id, published_item_id)
);

CREATE TABLE operation_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  operator_id BIGINT NOT NULL,
  organization_id BIGINT NOT NULL,
  action VARCHAR(60) NOT NULL,
  target_type VARCHAR(40) NOT NULL,
  target_id BIGINT NOT NULL,
  result VARCHAR(30) NOT NULL,
  ip VARCHAR(64),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_document_org ON source_document(organization_id);
CREATE INDEX idx_document_status ON source_document(processing_status);
CREATE INDEX idx_field_document ON extracted_field(document_id);
CREATE INDEX idx_published_status ON published_item(status, published_at);
