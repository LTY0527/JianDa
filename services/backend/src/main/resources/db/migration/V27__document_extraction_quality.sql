ALTER TABLE document_segment ADD COLUMN raw_text LONGTEXT;

ALTER TABLE source_document ADD COLUMN ocr_page_count INT NOT NULL DEFAULT 0;
ALTER TABLE source_document ADD COLUMN extraction_quality_json LONGTEXT;
