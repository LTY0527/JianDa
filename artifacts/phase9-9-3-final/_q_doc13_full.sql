SELECT id, processing_status, source_type, source_name,
       organization_name, publish_channel, suggested_publish_channel,
       channel_confidence, channel_reason, source_url,
       LENGTH(raw_text) AS raw_len, LENGTH(extracted_text) AS ext_len,
       original_published_at
FROM source_document WHERE id = 13\G
