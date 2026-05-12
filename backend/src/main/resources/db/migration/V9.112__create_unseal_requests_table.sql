-- F09.15 S1-A: unseal_requests（封緘解除二者承認）
-- 設計書: docs/features/F09.15_resident_succession_support.md §5.5
--
-- 同一ドメイン内 FK: pre_registration_id → succession_pre_registrations(id) ON DELETE CASCADE
-- 二段保護: requested_by / first_approver_user_id / second_approver_user_id の 3 者別人を
--   DB CHECK と Service 層で二段検証する。

CREATE TABLE unseal_requests (
    id                       BINARY(16) NOT NULL,
    organization_id          BIGINT UNSIGNED NOT NULL,
    dwelling_unit_id         BIGINT UNSIGNED NOT NULL,
    resident_registry_id     BIGINT UNSIGNED NOT NULL,
    pre_registration_id      BINARY(16) NOT NULL,
    requested_by             BIGINT UNSIGNED NOT NULL,
    request_reason           TEXT NOT NULL,
    first_approver_user_id   BIGINT UNSIGNED NULL,
    first_approved_at        DATETIME(6) NULL,
    second_approver_user_id  BIGINT UNSIGNED NULL,
    second_approved_at       DATETIME(6) NULL,
    unseal_completed_at      DATETIME(6) NULL,
    auto_reseal_at           DATETIME(6) NULL,
    re_sealed_at             DATETIME(6) NULL,
    rejected_at              DATETIME(6) NULL,
    rejected_by              BIGINT UNSIGNED NULL,
    deleted_at               DATETIME(6) NULL,
    created_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- 同一ドメイン内 UUIDv7 FK + CASCADE 許可（succession ドメイン内）
    CONSTRAINT fk_ur_pre_registration FOREIGN KEY (pre_registration_id)
        REFERENCES succession_pre_registrations (id) ON DELETE CASCADE,
    -- 3 者別人保証（DB CHECK・NULL 許容しつつ NOT NULL 時のみ検証）
    -- 設計書 §5.5 / §9.2 二段保護の DB 層
    CONSTRAINT chk_ur_three_distinct CHECK (
        first_approver_user_id IS NULL
        OR (
            first_approver_user_id <> requested_by
            AND (second_approver_user_id IS NULL OR (
                second_approver_user_id <> requested_by
                AND second_approver_user_id <> first_approver_user_id
            ))
        )
    )
);

CREATE INDEX idx_ur_org ON unseal_requests (organization_id, deleted_at);
CREATE INDEX idx_ur_dwelling ON unseal_requests (dwelling_unit_id);
CREATE INDEX idx_ur_pre_reg ON unseal_requests (pre_registration_id);
CREATE INDEX idx_ur_resident ON unseal_requests (resident_registry_id, unseal_completed_at DESC);
-- 72h 自動再封バッチ用（auto_reseal_at <= NOW かつ re_sealed_at IS NULL）
CREATE INDEX idx_ur_auto_reseal ON unseal_requests (auto_reseal_at, re_sealed_at);
