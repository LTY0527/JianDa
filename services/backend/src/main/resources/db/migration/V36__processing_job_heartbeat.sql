ALTER TABLE processing_job
  ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

CREATE INDEX idx_processing_job_status_heartbeat
  ON processing_job(status,updated_at,document_id);
