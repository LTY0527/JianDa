ALTER TABLE source_document ADD COLUMN source_name VARCHAR(160);
ALTER TABLE source_document ADD COLUMN document_number VARCHAR(80);
ALTER TABLE source_document ADD COLUMN source_type VARCHAR(60);
ALTER TABLE source_document ADD COLUMN authority_status VARCHAR(30);
ALTER TABLE source_document ADD COLUMN metadata_confidence DECIMAL(5,4);
ALTER TABLE source_document ADD COLUMN metadata_evidence_quote VARCHAR(500);
ALTER TABLE source_document ADD COLUMN metadata_evidence_type VARCHAR(30);
ALTER TABLE source_document ADD COLUMN metadata_page_no INT;
