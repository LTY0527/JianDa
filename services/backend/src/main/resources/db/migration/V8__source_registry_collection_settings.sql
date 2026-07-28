ALTER TABLE source_registry ADD COLUMN allow_auto_crawl BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE source_registry ADD COLUMN section_url VARCHAR(1000);
ALTER TABLE source_registry ADD COLUMN article_link_selector VARCHAR(500);
ALTER TABLE source_registry ADD COLUMN last_error VARCHAR(1000);
