CREATE TABLE ad_report_schedules (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    advertiser_account_id BIGINT UNSIGNED NOT NULL,
    frequency ENUM('WEEKLY','MONTHLY') NOT NULL,
    recipients JSON NOT NULL,
    include_campaigns JSON,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_sent_at DATETIME,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME,
    PRIMARY KEY (id),
    INDEX idx_account_enabled (advertiser_account_id, enabled, deleted_at),
    CONSTRAINT fk_ad_report_schedules_account FOREIGN KEY (advertiser_account_id) REFERENCES advertiser_accounts(id),
    CONSTRAINT fk_ad_report_schedules_user FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
