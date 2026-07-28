-- Bind the seven Phase 9 evaluation articles to the demo service institution.
-- Match canonical URLs instead of database IDs so the repair is repeatable and
-- does not affect unrelated WEB_ARTICLE records.
UPDATE source_document
SET organization_id = (
  SELECT organization_id
  FROM staff_user
  WHERE username = 'org_admin' AND status = 'ACTIVE'
  ORDER BY id
  LIMIT 1
)
WHERE source_type = 'WEB_ARTICLE'
  AND created_by = (
    SELECT id
    FROM staff_user
    WHERE username = 'platform_admin' AND status = 'ACTIVE'
    ORDER BY id
    LIMIT 1
  )
  AND canonical_url IN (
    'https://www.news.cn/local/20260726/5162716df0364f308bb24a99ef726b8f/c.html',
    'https://www.news.cn/politics/20260708/06f6db55ed624103892acb97e29e0592/c.html',
    'https://mzj.gz.gov.cn/zwgk/zfxxgkml/zfxxgkml/gzdt/content/post_10905430.html',
    'https://www.shanghai.gov.cn/nw15343/20260721/420c6fb341a44a6c9ef8b6705d58756f.html',
    'https://www.news.cn/yinling/20260715/75fbff39db554d84b415781cfe412f63/c.html',
    'https://www.news.cn/politics/20260708/9d0c1c824f2546d9b33ac5c99e643c42/c.html',
    'http://www.szlhq.gov.cn/jddt/content/post_12873915.html'
  )
  AND EXISTS (
    SELECT 1
    FROM staff_user
    WHERE username = 'org_admin' AND status = 'ACTIVE'
  );
