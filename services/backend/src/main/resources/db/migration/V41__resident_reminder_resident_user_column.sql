ALTER TABLE resident_reminder
  ADD COLUMN resident_user_id BIGINT NULL AFTER anonymous_user_id,
  ADD CONSTRAINT fk_reminder_resident_user
    FOREIGN KEY (resident_user_id) REFERENCES resident_user(id)
    ON DELETE CASCADE;

CREATE INDEX idx_reminder_resident_user_time
  ON resident_reminder(resident_user_id, remind_at);
