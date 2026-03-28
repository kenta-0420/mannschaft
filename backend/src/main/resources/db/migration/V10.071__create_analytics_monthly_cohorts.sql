CREATE TABLE analytics_monthly_cohorts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    cohort_month DATE NOT NULL,
    months_elapsed TINYINT UNSIGNED NOT NULL,
    cohort_size INT UNSIGNED NOT NULL DEFAULT 0,
    retained_users INT UNSIGNED NOT NULL DEFAULT 0,
    retained_paying INT UNSIGNED NOT NULL DEFAULT 0,
    revenue DECIMAL(12,2) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cohort_elapsed (cohort_month, months_elapsed),
    INDEX idx_cohort_month (cohort_month),
    CONSTRAINT chk_cohort_month_first CHECK (DAY(cohort_month) = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
