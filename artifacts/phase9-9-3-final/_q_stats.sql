SELECT 'published_by_channel' AS metric, publish_channel, COUNT(1) AS cnt FROM published_item WHERE status='PUBLISHED' GROUP BY publish_channel;
SELECT 'published_total' AS metric, COUNT(1) AS cnt FROM published_item WHERE status='PUBLISHED';
SELECT 'source_doc_by_status' AS metric, processing_status, COUNT(1) AS cnt FROM source_document GROUP BY processing_status;
SELECT 'waiting_review_web' AS metric, COUNT(1) AS cnt FROM source_document WHERE processing_status='WAITING_REVIEW' AND source_type='WEB_ARTICLE';
SELECT 'web_article_total' AS metric, COUNT(1) AS cnt FROM source_document WHERE source_type='WEB_ARTICLE';
SELECT 'published_channels_target' AS metric, publish_channel, COUNT(1) AS cnt FROM published_item WHERE status='PUBLISHED' GROUP BY publish_channel;
