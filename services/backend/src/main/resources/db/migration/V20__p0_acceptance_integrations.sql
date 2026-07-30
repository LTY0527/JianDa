ALTER TABLE source_registry ADD COLUMN allowed_hosts VARCHAR(2000);

ALTER TABLE ai_budget_reservation
  ADD COLUMN budget_exempt BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE processing_job ADD COLUMN reason_code VARCHAR(80);
ALTER TABLE processing_job ADD COLUMN provider_id VARCHAR(80);
ALTER TABLE processing_job ADD COLUMN model_id VARCHAR(120);
ALTER TABLE processing_job ADD COLUMN response_fingerprint VARCHAR(64);
ALTER TABLE processing_job
  ADD COLUMN crossed_provider_boundary BOOLEAN NOT NULL DEFAULT FALSE;
