ALTER TABLE processing_job ADD COLUMN fact_checkpoint_json LONGTEXT;
ALTER TABLE processing_job ADD COLUMN fact_response_fingerprint VARCHAR(64);
ALTER TABLE processing_job ADD COLUMN provider_request_id VARCHAR(160);
ALTER TABLE processing_job ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE processing_job ADD COLUMN last_failed_stage VARCHAR(50);
