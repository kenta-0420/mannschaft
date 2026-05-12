-- F09.15 S1-A: unseal_audit_views（開封中閲覧履歴・append-only）
-- 設計書: docs/features/F09.15_resident_succession_support.md §5.6
--
-- append-only テーブル。UPDATE / DELETE はアプリ層で禁止。
-- 同一ドメイン内 FK: unseal_request_id → unseal_requests(id) ON DELETE CASCADE
-- 注: updated_at は本テーブルでは設けない（append-only のため）。

CREATE TABLE unseal_audit_views (
    id                  BINARY(16) NOT NULL,
    organization_id     BIGINT UNSIGNED NOT NULL,
    unseal_request_id   BINARY(16) NOT NULL,
    viewer_user_id      BIGINT UNSIGNED NOT NULL,
    viewed_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ip_address          VARCHAR(45) NULL,
    user_agent          VARCHAR(500) NULL,
    request_id          VARCHAR(64) NULL,
    deleted_at          DATETIME(6) NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- 同一ドメイン内 UUIDv7 FK + CASCADE 許可
    CONSTRAINT fk_uav_request FOREIGN KEY (unseal_request_id)
        REFERENCES unseal_requests (id) ON DELETE CASCADE
);

CREATE INDEX idx_uav_org ON unseal_audit_views (organization_id);
CREATE INDEX idx_uav_request ON unseal_audit_views (unseal_request_id, viewed_at DESC);
CREATE INDEX idx_uav_viewer ON unseal_audit_views (viewer_user_id, viewed_at DESC);
