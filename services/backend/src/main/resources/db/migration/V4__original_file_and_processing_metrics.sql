ALTER TABLE source_document ADD COLUMN original_filename VARCHAR(255);
ALTER TABLE source_document ADD COLUMN mime_type VARCHAR(100);
ALTER TABLE source_document ADD COLUMN file_size BIGINT;
ALTER TABLE source_document ADD COLUMN file_sha256 CHAR(64);
ALTER TABLE source_document ADD COLUMN allow_public_original BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE processing_job ADD COLUMN stage VARCHAR(50);
ALTER TABLE processing_job ADD COLUMN trace_id VARCHAR(80);
ALTER TABLE processing_job ADD COLUMN schema_version VARCHAR(20);
ALTER TABLE processing_job ADD COLUMN cache_hit BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE processing_job ADD COLUMN text_extract_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE processing_job ADD COLUMN fact_extract_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE processing_job ADD COLUMN trace_validation_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE processing_job ADD COLUMN accessible_rewrite_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE processing_job ADD COLUMN persistence_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE processing_job ADD COLUMN total_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE processing_job ADD COLUMN prompt_tokens INT NOT NULL DEFAULT 0;
ALTER TABLE processing_job ADD COLUMN completion_tokens INT NOT NULL DEFAULT 0;
ALTER TABLE processing_job ADD COLUMN total_tokens INT NOT NULL DEFAULT 0;
