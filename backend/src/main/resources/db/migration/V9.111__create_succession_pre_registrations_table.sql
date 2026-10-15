-- F09.15 S1-A: succession_pre_registrations（事前登録・封緘）
-- 設計書: docs/features/F09.15_resident_succession_support.md §5.4
--
-- 暗号化フィールド: emergency_contacts / inheritance_candidates / will_memo / frozen_account_info
--   → AES-256-GCM @Encrypted（@Convert(EncryptedStringConverter.class)）
-- expected_absence_periods は JSON（推定スコアから除外する期間配列・暗号化不要）
-- 1 居住者 1 事前登録（UNIQUE）

CREATE TABLE succession_pre_registrations (
    id                          BINARY(16) NOT NULL,
    organization_id             BIGINT UNSIGNED NOT NULL,
    dwelling_unit_id            BIGINT UNSIGNED NOT NULL,
    resident_registry_id        BIGINT UNSIGNED NOT NULL,
    owner_user_id               BIGINT UNSIGNED NOT NULL,
    seal_status                 VARCHAR(20) NOT NULL DEFAULT 'SEALED',
    emergency_contacts          TEXT NULL,
    inheritance_candidates      TEXT NULL,
    will_memo                   TEXT NULL,
    frozen_account_info         TEXT NULL,
    expected_absence_periods    JSON NULL,
    last_updated_by_owner_at    DATETIME(6) NULL,
    auto_reseal_at              DATETIME(6) NULL,
    deleted_at                  DATETIME(6) NULL,
    created_at                  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- 封緘状態 enum 制約（設計書 §4 封緘状態 enum）
    CONSTRAINT chk_spr_seal_status CHECK (seal_status IN (
        'SEALED', 'UNSEAL_REQUESTED', 'UNSEALED', 'RE_SEALED'
    ))
);

-- 1 居住者 1 事前登録（論理削除を考慮した複合 UNIQUE）
CREATE UNIQUE INDEX uq_spr_resident ON succession_pre_registrations (resident_registry_id, deleted_at);

CREATE INDEX idx_spr_org ON succession_pre_registrations (organization_id, deleted_at);
CREATE INDEX idx_spr_dwelling ON succession_pre_registrations (dwelling_unit_id);
CREATE INDEX idx_spr_owner ON succession_pre_registrations (owner_user_id);
-- 72h 自動再封バッチ用（seal_status=UNSEALED かつ auto_reseal_at <= NOW を走査）
CREATE INDEX idx_spr_seal ON succession_pre_registrations (seal_status, auto_reseal_at);
