SELECT p.id AS pub_id, p.document_id, p.publish_channel, p.slug, p.status AS pub_status,
       d.processing_status, d.source_url, d.title
FROM published_item p JOIN source_document d ON d.id=p.document_id
WHERE p.document_id=76;
