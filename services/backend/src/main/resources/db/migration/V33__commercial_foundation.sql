CREATE TABLE organization_plan (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_code VARCHAR(60) NOT NULL UNIQUE,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(500) NULL,
  seat_limit INT NOT NULL DEFAULT 1,
  region_limit INT NOT NULL DEFAULT 1,
  content_limit INT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE organization_subscription (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  organization_id BIGINT NOT NULL,
  plan_id BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'INACTIVE',
  starts_at TIMESTAMP NULL,
  expires_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_subscription_org FOREIGN KEY (organization_id) REFERENCES organization(id),
  CONSTRAINT fk_subscription_plan FOREIGN KEY (plan_id) REFERENCES organization_plan(id)
);

CREATE TABLE organization_seat (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  subscription_id BIGINT NOT NULL,
  staff_user_id BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
  assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_seat_subscription FOREIGN KEY (subscription_id) REFERENCES organization_subscription(id),
  CONSTRAINT fk_seat_staff FOREIGN KEY (staff_user_id) REFERENCES staff_user(id)
);

CREATE TABLE service_provider (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(160) NOT NULL,
  verification_status VARCHAR(30) NOT NULL DEFAULT 'WAITING_REVIEW',
  contact_phone VARCHAR(60) NULL,
  qualification_note VARCHAR(1000) NULL,
  refund_policy VARCHAR(1000) NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'INACTIVE',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE service_product (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider_id BIGINT NOT NULL,
  name VARCHAR(160) NOT NULL,
  category VARCHAR(60) NOT NULL,
  description VARCHAR(2000) NOT NULL,
  region_code VARCHAR(20) NOT NULL,
  service_area VARCHAR(500) NOT NULL,
  price_cents BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_product_provider FOREIGN KEY (provider_id) REFERENCES service_provider(id)
);

CREATE TABLE sponsor_campaign (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  sponsor_name VARCHAR(160) NOT NULL,
  title VARCHAR(200) NOT NULL,
  description VARCHAR(1000) NOT NULL,
  image_url VARCHAR(1000) NULL,
  target_url VARCHAR(1000) NULL,
  label VARCHAR(40) NOT NULL,
  region_code VARCHAR(20) NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
  start_at TIMESTAMP NULL,
  end_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE service_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(50) NOT NULL UNIQUE,
  resident_user_id BIGINT NOT NULL,
  provider_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  region_code VARCHAR(20) NOT NULL,
  quantity INT NOT NULL,
  amount_cents BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'PENDING_PAYMENT',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  cancelled_at TIMESTAMP NULL,
  completed_at TIMESTAMP NULL,
  CONSTRAINT fk_order_resident FOREIGN KEY (resident_user_id) REFERENCES resident_user(id),
  CONSTRAINT fk_order_provider FOREIGN KEY (provider_id) REFERENCES service_provider(id),
  CONSTRAINT fk_order_product FOREIGN KEY (product_id) REFERENCES service_product(id)
);

CREATE TABLE payment_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  service_order_id BIGINT NOT NULL,
  provider_code VARCHAR(40) NOT NULL,
  provider_order_no VARCHAR(100) NULL,
  amount_cents BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'NOT_CREATED',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_payment_service_order FOREIGN KEY (service_order_id) REFERENCES service_order(id)
);

CREATE TABLE payment_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  payment_order_id BIGINT NOT NULL,
  event_type VARCHAR(60) NOT NULL,
  provider_event_id VARCHAR(120) NULL,
  signature_verified BOOLEAN NOT NULL DEFAULT FALSE,
  event_summary VARCHAR(500) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_payment_event_order FOREIGN KEY (payment_order_id) REFERENCES payment_order(id)
);

CREATE TABLE refund_request (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  service_order_id BIGINT NOT NULL,
  resident_user_id BIGINT NOT NULL,
  reason VARCHAR(500) NOT NULL,
  amount_cents BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'REQUESTED',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at TIMESTAMP NULL,
  CONSTRAINT fk_refund_service_order FOREIGN KEY (service_order_id) REFERENCES service_order(id),
  CONSTRAINT fk_refund_resident FOREIGN KEY (resident_user_id) REFERENCES resident_user(id)
);

CREATE INDEX idx_service_product_region ON service_product(region_code,status);
CREATE INDEX idx_service_order_resident ON service_order(resident_user_id,created_at);
CREATE INDEX idx_sponsor_region_status ON sponsor_campaign(region_code,status,start_at,end_at);
