-- F07.2 パフォーマンス管理: 月次集計サマリーテーブル
CREATE TABLE performance_monthly_summaries (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    metric_id     BIGINT UNSIGNED NOT NULL,
    user_id       BIGINT UNSIGNED NOT NULL,
    year_month    CHAR(7)         NOT NULL,
    sum_value     DECIMAL(15,4)   NOT NULL DEFAULT 0,
    avg_value     DECIMAL(15,4)   NOT NULL DEFAULT 0,
    max_value     DECIMAL(15,4)   NULL,
    min_value     DECIMAL(15,4)   NULL,
    latest_value  DECIMAL(15,4)   NULL,
    record_count  INT UNSIGNED    NOT NULL DEFAULT 0,
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_pms_metric FOREIGN KEY (metric_id) REFERENCES performance_metrics (id) ON DELETE CASCADE,
    CONSTRAINT fk_pms_user   FOREIGN KEY (user_id)   REFERENCES users (id)               ON DELETE CASCADE,
    UNIQUE INDEX uq_pms_metric_user_month (metric_id, user_id, year_month),
    INDEX idx_pms_user_month (user_id, year_month DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
