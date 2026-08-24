CREATE TABLE membership_plan (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_code VARCHAR(40) NOT NULL UNIQUE,
  name VARCHAR(80) NOT NULL,
  billing_period VARCHAR(20) NOT NULL,
  duration_days INT NOT NULL,
  price_cents BIGINT NOT NULL,
  original_price_cents BIGINT NULL,
  benefits_json TEXT NOT NULL,
  demo_price BOOLEAN NOT NULL DEFAULT FALSE,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE resident_membership (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  resident_user_id BIGINT NOT NULL,
  plan_id BIGINT NOT NULL,
  status VARCHAR(40) NOT NULL,
  starts_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_membership_resident FOREIGN KEY (resident_user_id) REFERENCES resident_user(id),
  CONSTRAINT fk_membership_plan FOREIGN KEY (plan_id) REFERENCES membership_plan(id)
);

CREATE TABLE demo_payment_session (
  id VARCHAR(80) PRIMARY KEY,
  resident_user_id BIGINT NOT NULL,
  plan_id BIGINT NOT NULL,
  payment_method VARCHAR(20) NOT NULL,
  amount_cents BIGINT NOT NULL,
  qr_payload VARCHAR(300) NOT NULL,
  status VARCHAR(40) NOT NULL DEFAULT 'DEMO_PENDING',
  expires_at TIMESTAMP NOT NULL,
  confirmed_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_demo_payment_resident FOREIGN KEY (resident_user_id) REFERENCES resident_user(id),
  CONSTRAINT fk_demo_payment_plan FOREIGN KEY (plan_id) REFERENCES membership_plan(id)
);

CREATE INDEX idx_membership_resident_status ON resident_membership(resident_user_id,status,expires_at);
CREATE INDEX idx_demo_payment_resident_status ON demo_payment_session(resident_user_id,status,expires_at);

INSERT INTO membership_plan(plan_code,name,billing_period,duration_days,price_cents,original_price_cents,benefits_json,demo_price,enabled,sort_order)
VALUES
('WEEK_DEMO','周卡','WEEK',7,290,NULL,'["减少合作推广位","阅读和语音偏好云端同步"]',TRUE,TRUE,10),
('MONTH_DEMO','月卡','MONTH',30,790,1160,'["减少合作推广位","阅读和语音偏好云端同步"]',TRUE,TRUE,20),
('YEAR_DEMO','年卡','YEAR',365,5900,9480,'["隐藏合作推广位","阅读和语音偏好云端同步"]',TRUE,TRUE,30);
