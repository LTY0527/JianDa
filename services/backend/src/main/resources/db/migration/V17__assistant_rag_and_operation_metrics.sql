CREATE TABLE assistant_query_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    question_category VARCHAR(40) NOT NULL,
    context_slug VARCHAR(180),
    mode VARCHAR(20) NOT NULL,
    evidence_count INT NOT NULL DEFAULT 0,
    citation_count INT NOT NULL DEFAULT 0,
    success BOOLEAN NOT NULL DEFAULT FALSE,
    model_id VARCHAR(120),
    request_id VARCHAR(160),
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    total_tokens INT NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(80),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_assistant_query_created ON assistant_query_event(created_at);
CREATE INDEX idx_assistant_query_mode ON assistant_query_event(mode);

CREATE TABLE content_engagement_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    published_item_id BIGINT NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_engagement_item FOREIGN KEY (published_item_id) REFERENCES published_item(id)
);

CREATE INDEX idx_engagement_item_type ON content_engagement_event(published_item_id, event_type);
CREATE INDEX idx_engagement_created ON content_engagement_event(created_at);

CREATE TABLE daily_operation_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_date DATE NOT NULL,
    metrics_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_operation_snapshot_date UNIQUE (snapshot_date)
);
