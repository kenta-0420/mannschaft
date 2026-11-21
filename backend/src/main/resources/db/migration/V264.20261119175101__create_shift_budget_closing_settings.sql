-- CMP-260909-2152 α: 組織ごとの締め日設定の先頭行（締め・変更の直列化ロック対象）。組織1行。INSERT IGNORE で必要時に作成する。
CREATE TABLE shift_budget_closing_settings (
    id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    revision INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_sbcs_organization (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
