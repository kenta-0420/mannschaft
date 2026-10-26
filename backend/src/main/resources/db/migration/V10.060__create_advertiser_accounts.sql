CREATE TABLE advertiser_accounts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    status ENUM('PENDING', 'ACTIVE', 'SUSPENDED') NOT NULL DEFAULT 'PENDING',
    company_name VARCHAR(200) NOT NULL,
    contact_email VARCHAR(254) NOT NULL,
    billing_method ENUM('STRIPE', 'INVOICE') NOT NULL DEFAULT 'STRIPE',
    stripe_customer_id VARCHAR(50) NULL,
    credit_limit DECIMAL(12,2) NOT NULL DEFAULT 100000,
    approved_by BIGINT UNSIGNED NULL,
    approved_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_advertiser_accounts_organization FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_advertiser_accounts_approved_by FOREIGN KEY (approved_by) REFERENCES users(id),
    CONSTRAINT chk_advertiser_accounts_credit_limit CHECK (credit_limit > 0)
);
CREATE INDEX idx_advertiser_accounts_status ON advertiser_accounts(status, deleted_at);
