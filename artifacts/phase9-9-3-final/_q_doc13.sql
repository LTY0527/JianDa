SELECT id, processing_status, source_type, source_name, title,
       LEFT(raw_text, 80) AS raw_preview,
       publish_channel, suggested_publish_channel, channel_confidence
FROM source_document WHERE id IN (13, 42, 43, 44, 54, 55, 56, 57, 58, 73, 75, 77);
