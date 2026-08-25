ALTER TABLE crawl_job ADD COLUMN progress_message VARCHAR(200);
ALTER TABLE crawl_job ADD COLUMN discovery_result_json LONGTEXT;
