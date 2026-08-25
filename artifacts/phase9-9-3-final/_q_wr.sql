SELECT d.id, d.source_type, d.suggested_publish_channel, d.channel_confidence,
       (SELECT COUNT(1) FROM generated_content g WHERE g.document_id=d.id) AS gen_count,
       (SELECT COUNT(1) FROM extracted_field f WHERE f.document_id=d.id) AS field_count,
       LEFT(d.title,50) AS title_preview
FROM source_document d
WHERE d.processing_status='WAITING_REVIEW'
ORDER BY d.id;
