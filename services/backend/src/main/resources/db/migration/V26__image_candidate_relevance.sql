ALTER TABLE image_candidate ADD COLUMN context_text VARCHAR(1000);
ALTER TABLE image_candidate ADD COLUMN relevance_score INT NOT NULL DEFAULT 0;
