ALTER TABLE source_registry ADD COLUMN source_type VARCHAR(40) NOT NULL DEFAULT 'PUBLIC_INSTITUTION';
ALTER TABLE source_registry ADD COLUMN homepage_url VARCHAR(1000);
ALTER TABLE source_registry ADD COLUMN rss_url VARCHAR(1000);
ALTER TABLE source_registry ADD COLUMN sitemap_url VARCHAR(1000);
ALTER TABLE source_registry ADD COLUMN allow_auto_ai BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE source_registry ADD COLUMN daily_article_budget INT NOT NULL DEFAULT 0;
ALTER TABLE source_registry ADD COLUMN daily_token_budget INT NOT NULL DEFAULT 0;
ALTER TABLE source_registry ADD COLUMN last_status VARCHAR(30) NOT NULL DEFAULT 'NEVER_RUN';
ALTER TABLE source_registry ADD COLUMN lock_owner VARCHAR(100);
ALTER TABLE source_registry ADD COLUMN lock_until TIMESTAMP NULL;
ALTER TABLE source_registry ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE source_registry SET homepage_url=CONCAT('https://', domain) WHERE homepage_url IS NULL;

CREATE INDEX idx_source_registry_lease ON source_registry(id, enabled, lock_until);
