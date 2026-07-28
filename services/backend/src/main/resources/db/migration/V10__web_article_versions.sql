DROP INDEX uk_web_article_canonical ON source_document;

ALTER TABLE source_document ADD COLUMN previous_version_id BIGINT NULL;

CREATE INDEX idx_web_article_canonical ON source_document(canonical_url);
CREATE INDEX idx_web_article_previous_version ON source_document(previous_version_id);
