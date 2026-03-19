-- F02.2: ダッシュボードウィジェット設定テーブル
CREATE TABLE dashboard_widget_settings (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NOT NULL,
    scope_type  VARCHAR(20)     NOT NULL COMMENT 'PERSONAL / TEAM / ORGANIZATION',
    scope_id    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'PERSONAL=0, TEAM/ORGANIZATION=実ID',
    widget_key  VARCHAR(50)     NOT NULL,
    is_visible  BOOLEAN         NOT NULL DEFAULT TRUE,
    sort_order  INT             NOT NULL DEFAULT 0,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_dws_user_scope_widget (user_id, scope_type, scope_id, widget_key),
    INDEX idx_dws_user_scope (user_id, scope_type, scope_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
