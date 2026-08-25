SELECT p.document_id, p.publish_channel, p.importance_level,
       LEFT(d.title,60) AS title_preview,
       CASE WHEN d.title REGEXP '(?i)(smoke|test|demo|mock|fixture|示例|演示|模拟|测试)' THEN 'SUSPICIOUS' ELSE 'ok' END AS title_check,
       d.source_type, d.source_name
FROM published_item p JOIN source_document d ON d.id=p.document_id
WHERE p.status='PUBLISHED'
ORDER BY title_check DESC, p.document_id;
