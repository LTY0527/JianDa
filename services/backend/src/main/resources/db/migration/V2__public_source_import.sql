ALTER TABLE content_source ADD COLUMN whitelist_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED';
ALTER TABLE content_source ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE content_source ADD COLUMN last_imported_at TIMESTAMP NULL;
ALTER TABLE content_source ADD COLUMN notes VARCHAR(1000) NULL;
ALTER TABLE content_source ADD COLUMN created_by BIGINT NULL;

ALTER TABLE source_document ADD COLUMN import_url VARCHAR(500) NULL;
ALTER TABLE source_document ADD COLUMN source_published_at TIMESTAMP NULL;
ALTER TABLE source_document ADD COLUMN imported_at TIMESTAMP NULL;
ALTER TABLE source_document ADD COLUMN content_hash VARCHAR(64) NULL;
ALTER TABLE source_document ADD COLUMN category VARCHAR(40) NULL;
ALTER TABLE source_document ADD COLUMN import_method VARCHAR(30) NULL;

CREATE INDEX idx_source_enabled ON content_source(enabled, whitelist_status);
CREATE INDEX idx_document_import_url ON source_document(import_url);
CREATE INDEX idx_document_content_hash ON source_document(content_hash);
