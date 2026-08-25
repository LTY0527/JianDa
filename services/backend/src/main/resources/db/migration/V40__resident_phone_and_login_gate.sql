ALTER TABLE resident_user ADD COLUMN phone VARCHAR(20) NULL;
ALTER TABLE resident_user ADD CONSTRAINT uk_resident_user_phone UNIQUE (phone);
CREATE INDEX idx_resident_user_phone ON resident_user(phone);
