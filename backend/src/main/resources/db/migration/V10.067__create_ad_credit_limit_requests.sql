CREATE TABLE ad_credit_limit_requests (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    advertiser_account_id BIGINT UNSIGNED NOT NULL,
    current_limit DECIMAL(12,2) NOT NULL,
    requested_limit DECIMAL(12,2) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
    reviewed_by BIGINT UNSIGNED,
    reviewed_at DATETIME,
    review_note VARCHAR(500),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_account_status (advertiser_account_id, status),
    CONSTRAINT fk_ad_credit_requests_account FOREIGN KEY (advertiser_account_id) REFERENCES advertiser_accounts(id),
    CONSTRAINT fk_ad_credit_requests_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id),
    CONSTRAINT chk_requested_gt_current CHECK (requested_limit > current_limit)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
