CREATE TABLE resident_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(60) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  nickname VARCHAR(60) NOT NULL,
  avatar VARCHAR(255) NULL,
  district VARCHAR(60) NOT NULL,
  street_or_town VARCHAR(80) NOT NULL,
  community VARCHAR(100) NULL,
  region_code VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  is_demo BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE resident_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  resident_user_id BIGINT NOT NULL,
  token_hash VARCHAR(64) NOT NULL UNIQUE,
  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_resident_session_user FOREIGN KEY (resident_user_id) REFERENCES resident_user(id)
);

CREATE TABLE community_post (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  resident_user_id BIGINT NOT NULL,
  category VARCHAR(20) NOT NULL DEFAULT '最新',
  content VARCHAR(500) NOT NULL,
  region_code VARCHAR(20) NOT NULL,
  district VARCHAR(60) NOT NULL,
  street_or_town VARCHAR(80) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'VISIBLE',
  is_demo BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_community_post_user FOREIGN KEY (resident_user_id) REFERENCES resident_user(id)
);

CREATE TABLE community_post_like (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_post_id BIGINT NOT NULL,
  resident_user_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_post_like_post FOREIGN KEY (community_post_id) REFERENCES community_post(id),
  CONSTRAINT fk_post_like_user FOREIGN KEY (resident_user_id) REFERENCES resident_user(id),
  CONSTRAINT uk_post_like UNIQUE (community_post_id, resident_user_id)
);

CREATE TABLE community_comment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_post_id BIGINT NOT NULL,
  resident_user_id BIGINT NOT NULL,
  content VARCHAR(300) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'VISIBLE',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_comment_post FOREIGN KEY (community_post_id) REFERENCES community_post(id),
  CONSTRAINT fk_comment_user FOREIGN KEY (resident_user_id) REFERENCES resident_user(id)
);

CREATE TABLE community_report (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  community_post_id BIGINT NOT NULL,
  resident_user_id BIGINT NOT NULL,
  reason VARCHAR(200) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_report_post FOREIGN KEY (community_post_id) REFERENCES community_post(id),
  CONSTRAINT fk_report_user FOREIGN KEY (resident_user_id) REFERENCES resident_user(id)
);

CREATE INDEX idx_community_post_region_time ON community_post(region_code, status, created_at);
CREATE INDEX idx_community_report_status ON community_report(status, created_at);
