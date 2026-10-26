-- F07.2 パフォーマンス管理: 指標テンプレートテーブル
CREATE TABLE performance_metric_templates (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    sport_category    VARCHAR(50)     NOT NULL,
    group_name        VARCHAR(50)     NULL,
    name              VARCHAR(100)    NOT NULL,
    unit              VARCHAR(30)     NULL,
    data_type         ENUM('INTEGER', 'DECIMAL', 'TIME') NOT NULL DEFAULT 'DECIMAL',
    aggregation_type  ENUM('SUM', 'AVG', 'MAX', 'MIN', 'LATEST') NOT NULL DEFAULT 'SUM',
    description       VARCHAR(500)    NULL,
    sort_order        INT             NOT NULL DEFAULT 0,
    min_value         DECIMAL(15,4)   NULL,
    max_value         DECIMAL(15,4)   NULL,
    is_self_recordable BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_pmt_sport (sport_category, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
