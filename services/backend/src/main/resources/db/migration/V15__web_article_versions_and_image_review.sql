ALTER TABLE source_document ADD COLUMN version_root_id BIGINT NULL;
ALTER TABLE source_document ADD COLUMN version_no INT NOT NULL DEFAULT 1;
ALTER TABLE source_document ADD COLUMN old_content_hash CHAR(64);
ALTER TABLE source_document ADD COLUMN new_content_hash CHAR(64);
ALTER TABLE source_document ADD COLUMN content_change_summary VARCHAR(1000);
ALTER TABLE source_document ADD COLUMN version_created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE source_document ADD COLUMN superseded_at TIMESTAMP NULL;

ALTER TABLE image_candidate ADD COLUMN mime_type VARCHAR(100);
ALTER TABLE image_candidate ADD COLUMN image_cached BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE image_candidate ADD COLUMN priority_rank INT NOT NULL DEFAULT 100;
ALTER TABLE image_candidate ADD COLUMN candidate_status VARCHAR(30) NOT NULL DEFAULT 'VALID';
ALTER TABLE image_candidate ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE source_document SET version_root_id=id WHERE source_type='WEB_ARTICLE' AND version_root_id IS NULL;

CREATE INDEX idx_web_article_version_root ON source_document(version_root_id,version_no);
CREATE UNIQUE INDEX uk_web_article_version_number ON source_document(version_root_id,version_no);
CREATE INDEX idx_image_candidate_review ON image_candidate(document_id,review_status,priority_rank);
