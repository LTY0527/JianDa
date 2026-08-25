UPDATE source_registry
SET allow_image_candidates = TRUE,
    allow_image_cache = TRUE,
    image_cache_allowed = TRUE,
    image_usage_policy = CASE
      WHEN image_usage_policy IS NULL OR image_usage_policy = 'MANUAL_REVIEW'
        THEN 'LOCAL_DEMO_CONFIRMED'
      ELSE image_usage_policy
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE authority_level IN ('A', 'B')
  AND source_type IN (
    'GOVERNMENT', 'PUBLIC_INSTITUTION', 'HOSPITAL', 'COMMUNITY_HEALTH', 'CDC',
    'ELDERLY_SERVICE_ORG', 'OFFICIAL_MEDIA', 'OFFICIAL_WECHAT',
    'UNIVERSITY_PUBLIC_SERVICE', 'OTHER_VERIFIED_OFFICIAL', 'MAINSTREAM_MEDIA',
    'ANTI_FRAUD', 'ELDERLY_CARE', 'OTHER_PUBLIC_SERVICE'
  );

UPDATE source_registry
SET enabled = TRUE,
    allow_auto_crawl = TRUE,
    allow_image_candidates = TRUE,
    allow_image_cache = TRUE,
    image_cache_allowed = TRUE,
    crawl_mode = 'SCHEDULED',
    discovery_mode = 'SECTION',
    schedule_mode = 'INTERVAL',
    interval_hours = 12,
    next_run_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE domain = 'xxgk.shbsq.gov.cn';
