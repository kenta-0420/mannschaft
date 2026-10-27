-- 一般機能の画面表示をスコープとロール別に整理する。業務APIの認可は変更しない。
CREATE TABLE scope_feature_display_policies (
    id BINARY(16) NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    feature_key VARCHAR(64) NOT NULL,
    member_visible BOOLEAN NOT NULL DEFAULT TRUE,
    supporter_visible BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_sfdp_scope_feature UNIQUE (scope_type, scope_id, feature_key),
    CONSTRAINT chk_sfdp_scope_type CHECK (scope_type IN ('TEAM', 'ORGANIZATION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
