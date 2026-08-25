SELECT id, title, LEFT(raw_text, 400) AS preview, original_url, source_url
FROM source_document
WHERE id IN (13, 42, 43, 44, 54, 55, 56, 57, 58, 73, 75, 77)
ORDER BY id\G
