CREATE TABLE community_post_media (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  resident_user_id BIGINT NOT NULL,
  community_post_id BIGINT NULL,
  original_filename VARCHAR(255) NOT NULL,
  mime_type VARCHAR(40) NOT NULL,
  file_size BIGINT NOT NULL,
  width INT NOT NULL,
  height INT NOT NULL,
  storage_path VARCHAR(1000) NOT NULL,
  thumbnail_path VARCHAR(1000) NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  bound_at TIMESTAMP NULL,
  CONSTRAINT fk_post_media_owner FOREIGN KEY (resident_user_id) REFERENCES resident_user(id),
  CONSTRAINT fk_post_media_post FOREIGN KEY (community_post_id) REFERENCES community_post(id)
);

CREATE INDEX idx_post_media_post ON community_post_media(community_post_id, id);
CREATE INDEX idx_post_media_owner_unbound ON community_post_media(resident_user_id, community_post_id, created_at);
