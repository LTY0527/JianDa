ALTER TABLE published_item ADD COLUMN effective_from TIMESTAMP NULL;
ALTER TABLE published_item ADD COLUMN deadline_at TIMESTAMP NULL;
ALTER TABLE published_item ADD COLUMN expires_at TIMESTAMP NULL;
ALTER TABLE published_item ADD COLUMN last_verified_at TIMESTAMP NULL;
ALTER TABLE published_item ADD COLUMN source_updated_at TIMESTAMP NULL;
ALTER TABLE published_item ADD COLUMN verification_status VARCHAR(30) NOT NULL DEFAULT 'VERIFIED';

CREATE INDEX idx_published_lifecycle ON published_item(status, expires_at, deadline_at, published_at);
