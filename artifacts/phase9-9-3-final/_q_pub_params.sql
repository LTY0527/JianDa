SELECT id, title, category, source_name, source_type, original_url,
       publish_channel, suggested_publish_channel,
       province, city, district, street_or_town, region_code, local_scope,
       cover_image_url, content_kind
FROM source_document WHERE id IN (73, 75)\G
