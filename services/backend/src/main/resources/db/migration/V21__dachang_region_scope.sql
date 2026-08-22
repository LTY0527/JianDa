ALTER TABLE source_document ADD COLUMN province VARCHAR(40);
ALTER TABLE source_document ADD COLUMN city VARCHAR(40);
ALTER TABLE source_document ADD COLUMN district VARCHAR(60);
ALTER TABLE source_document ADD COLUMN street_or_town VARCHAR(80);
ALTER TABLE source_document ADD COLUMN community VARCHAR(100);
ALTER TABLE source_document ADD COLUMN region_code VARCHAR(20);
ALTER TABLE source_document ADD COLUMN local_scope VARCHAR(20) NOT NULL DEFAULT 'UNSPECIFIED';

ALTER TABLE published_item ADD COLUMN province VARCHAR(40);
ALTER TABLE published_item ADD COLUMN city VARCHAR(40);
ALTER TABLE published_item ADD COLUMN district VARCHAR(60);
ALTER TABLE published_item ADD COLUMN street_or_town VARCHAR(80);
ALTER TABLE published_item ADD COLUMN community VARCHAR(100);
ALTER TABLE published_item ADD COLUMN region_code VARCHAR(20);
ALTER TABLE published_item ADD COLUMN local_scope VARCHAR(20) NOT NULL DEFAULT 'UNSPECIFIED';

ALTER TABLE source_registry ADD COLUMN province VARCHAR(40);
ALTER TABLE source_registry ADD COLUMN city VARCHAR(40);
ALTER TABLE source_registry ADD COLUMN district VARCHAR(60);
ALTER TABLE source_registry ADD COLUMN street_or_town VARCHAR(80);
ALTER TABLE source_registry ADD COLUMN region_code VARCHAR(20);

CREATE INDEX idx_published_region ON published_item(status, region_code, published_at);

INSERT INTO source_registry(
  domain,source_name,source_type,authority_level,enabled,crawl_mode,discovery_mode,
  homepage_url,section_url,max_articles_per_run,allow_auto_crawl,allow_auto_ai,
  allow_image_candidates,allow_image_cache,image_cache_allowed,requires_manual_review,
  province,city,district,street_or_town,region_code
)
SELECT
  'xxgk.shbsq.gov.cn','宝山区政府信息公开·大场镇','GOVERNMENT','A',FALSE,'MANUAL','SECTION',
  'https://xxgk.shbsq.gov.cn/',
  'https://xxgk.shbsq.gov.cn/infoDirectory.html?dept=003006&rn=%E5%A4%A7%E5%9C%BA%E9%95%87&type=dept',
  5,FALSE,FALSE,FALSE,FALSE,FALSE,TRUE,
  '上海市','上海市','宝山区','大场镇','310113102'
WHERE NOT EXISTS (SELECT 1 FROM source_registry WHERE domain='xxgk.shbsq.gov.cn');
