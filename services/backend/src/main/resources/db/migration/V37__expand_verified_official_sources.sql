-- Phase 9.9.2: verified official source registry expansion.
-- Every source remains disabled and manual-review-only until an operator completes a shadow run.
INSERT INTO source_registry(
  domain,source_name,source_type,authority_level,enabled,crawl_mode,discovery_mode,
  homepage_url,section_url,max_articles_per_run,allow_auto_crawl,allow_auto_ai,
  allow_image_candidates,allow_image_cache,image_cache_allowed,requires_manual_review,
  schedule_mode,interval_hours,recent_days,include_keywords,exclude_keywords,
  province,city,district,region_code,last_status
)
SELECT 'www.gov.cn','中国政府网·政策','GOVERNMENT','A',FALSE,'MANUAL','SECTION',
  'https://www.gov.cn/','https://www.gov.cn/zhengce/',10,FALSE,FALSE,TRUE,FALSE,FALSE,TRUE,
  'DAILY',24,7,'养老,医疗,医保,民政,社区,便民','采购,招标,人事任免',
  '全国',NULL,NULL,'100000','NEVER_RUN'
WHERE NOT EXISTS (SELECT 1 FROM source_registry WHERE domain='www.gov.cn' AND section_url='https://www.gov.cn/zhengce/');

INSERT INTO source_registry(
  domain,source_name,source_type,authority_level,enabled,crawl_mode,discovery_mode,
  homepage_url,section_url,max_articles_per_run,allow_auto_crawl,allow_auto_ai,
  allow_image_candidates,allow_image_cache,image_cache_allowed,requires_manual_review,
  schedule_mode,interval_hours,recent_days,include_keywords,exclude_keywords,
  province,city,district,region_code,last_status
)
SELECT 'www.nhc.gov.cn','国家卫生健康委·老年健康','GOVERNMENT','A',FALSE,'MANUAL','SECTION',
  'https://www.nhc.gov.cn/','https://www.nhc.gov.cn/wjw/lnrjk/list.shtml',10,FALSE,FALSE,TRUE,FALSE,FALSE,TRUE,
  'DAILY',24,30,'老年健康,健康服务,医疗卫生','采购,招标,人事任免',
  '全国',NULL,NULL,'100000','NEVER_RUN'
WHERE NOT EXISTS (SELECT 1 FROM source_registry WHERE domain='www.nhc.gov.cn' AND section_url='https://www.nhc.gov.cn/wjw/lnrjk/list.shtml');

INSERT INTO source_registry(
  domain,source_name,source_type,authority_level,enabled,crawl_mode,discovery_mode,
  homepage_url,section_url,max_articles_per_run,allow_auto_crawl,allow_auto_ai,
  allow_image_candidates,allow_image_cache,image_cache_allowed,requires_manual_review,
  schedule_mode,interval_hours,recent_days,include_keywords,exclude_keywords,
  province,city,district,region_code,last_status
)
SELECT 'mzj.sh.gov.cn','上海市民政局','GOVERNMENT','A',FALSE,'MANUAL','SECTION',
  'https://mzj.sh.gov.cn/','https://mzj.sh.gov.cn/',10,FALSE,FALSE,TRUE,FALSE,FALSE,TRUE,
  'DAILY',24,7,'养老,助餐,社区,社会救助,便民','采购,招标,人事任免',
  '上海市','上海市',NULL,'310000','NEVER_RUN'
WHERE NOT EXISTS (SELECT 1 FROM source_registry WHERE domain='mzj.sh.gov.cn' AND section_url='https://mzj.sh.gov.cn/');

INSERT INTO source_registry(
  domain,source_name,source_type,authority_level,enabled,crawl_mode,discovery_mode,
  homepage_url,section_url,max_articles_per_run,allow_auto_crawl,allow_auto_ai,
  allow_image_candidates,allow_image_cache,image_cache_allowed,requires_manual_review,
  schedule_mode,interval_hours,recent_days,include_keywords,exclude_keywords,
  province,city,district,region_code,last_status
)
SELECT 'wsjkw.sh.gov.cn','上海市卫生健康委员会','GOVERNMENT','A',FALSE,'MANUAL','SECTION',
  'https://wsjkw.sh.gov.cn/','https://wsjkw.sh.gov.cn/',10,FALSE,FALSE,TRUE,FALSE,FALSE,TRUE,
  'DAILY',24,7,'老年健康,医疗服务,疫苗,健康科普','采购,招标,人事任免',
  '上海市','上海市',NULL,'310000','NEVER_RUN'
WHERE NOT EXISTS (SELECT 1 FROM source_registry WHERE domain='wsjkw.sh.gov.cn' AND section_url='https://wsjkw.sh.gov.cn/');

INSERT INTO source_registry(
  domain,source_name,source_type,authority_level,enabled,crawl_mode,discovery_mode,
  homepage_url,section_url,max_articles_per_run,allow_auto_crawl,allow_auto_ai,
  allow_image_candidates,allow_image_cache,image_cache_allowed,requires_manual_review,
  schedule_mode,interval_hours,recent_days,include_keywords,exclude_keywords,
  province,city,district,region_code,last_status
)
SELECT 'ybj.sh.gov.cn','上海市医疗保障局','GOVERNMENT','A',FALSE,'MANUAL','SECTION',
  'https://ybj.sh.gov.cn/','https://ybj.sh.gov.cn/',10,FALSE,FALSE,TRUE,FALSE,FALSE,TRUE,
  'DAILY',24,7,'医保,医疗保障,异地就医,便民服务','采购,招标,人事任免',
  '上海市','上海市',NULL,'310000','NEVER_RUN'
WHERE NOT EXISTS (SELECT 1 FROM source_registry WHERE domain='ybj.sh.gov.cn' AND section_url='https://ybj.sh.gov.cn/');

INSERT INTO source_registry(
  domain,source_name,source_type,authority_level,enabled,crawl_mode,discovery_mode,
  homepage_url,section_url,max_articles_per_run,allow_auto_crawl,allow_auto_ai,
  allow_image_candidates,allow_image_cache,image_cache_allowed,requires_manual_review,
  schedule_mode,interval_hours,recent_days,include_keywords,exclude_keywords,
  province,city,district,region_code,last_status
)
SELECT 'www.shbsq.gov.cn','宝山区人民政府·区级信息','GOVERNMENT','A',FALSE,'MANUAL','SECTION',
  'https://www.shbsq.gov.cn/','https://www.shbsq.gov.cn/',10,FALSE,FALSE,TRUE,FALSE,FALSE,TRUE,
  'DAILY',24,7,'养老,社区,健康,便民,公共服务','采购,招标,人事任免',
  '上海市','上海市','宝山区','310113','NEVER_RUN'
WHERE NOT EXISTS (SELECT 1 FROM source_registry WHERE domain='www.shbsq.gov.cn' AND section_url='https://www.shbsq.gov.cn/');
