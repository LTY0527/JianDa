-- A successful processing result must not retain a transient stale-heartbeat
-- error that may have been written by an older application instance.
UPDATE processing_job
SET error_message = NULL,
    last_failed_stage = NULL
WHERE status = 'SUCCEEDED'
  AND (error_message IS NOT NULL OR last_failed_stage = 'HEARTBEAT_STALE');
