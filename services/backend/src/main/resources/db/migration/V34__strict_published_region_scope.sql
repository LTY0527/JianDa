ALTER TABLE source_document ALTER COLUMN local_scope SET DEFAULT 'UNCLASSIFIED';
ALTER TABLE published_item ALTER COLUMN local_scope SET DEFAULT 'UNCLASSIFIED';

UPDATE source_document SET local_scope='LOCAL_TOWN'
WHERE local_scope IN ('LOCAL','TOWN','STREET') AND region_code IS NOT NULL;
UPDATE published_item SET local_scope='LOCAL_TOWN'
WHERE local_scope IN ('LOCAL','TOWN','STREET') AND region_code IS NOT NULL;

UPDATE source_document SET local_scope='UNCLASSIFIED' WHERE local_scope='UNSPECIFIED';
UPDATE published_item SET local_scope='UNCLASSIFIED' WHERE local_scope='UNSPECIFIED';

CREATE INDEX idx_published_region_scope
  ON published_item(status,local_scope,region_code,district,city,published_at);
