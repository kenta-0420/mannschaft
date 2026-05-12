-- F09.15 S1-A: succession_covenants（入居時誓約・PDF 同梱保存）
-- 設計書: docs/features/F09.15_resident_succession_support.md §5.3
--
-- 共通仕様: UUIDv7 主キー（BINARY(16)）/ organization_id テナント絞り込み / 論理削除
-- クロスドメイン参照: dwelling_unit_id / resident_registry_id / signer_user_id は FK なし・INDEX のみ

CREATE TABLE succession_covenants (
    id                       BINARY(16) NOT NULL,
    organization_id          BIGINT UNSIGNED NOT NULL,
    dwelling_unit_id         BIGINT UNSIGNED NOT NULL,
    resident_registry_id     BIGINT UNSIGNED NOT NULL,
    signer_user_id           BIGINT UNSIGNED NOT NULL,
    covenant_type            VARCHAR(40) NOT NULL,
    covenant_version         VARCHAR(20) NOT NULL,
    pdf_s3_key               VARCHAR(500) NOT NULL,
    pdf_sha256               CHAR(64) NOT NULL,
    internal_signature_token VARCHAR(500) NOT NULL,
    signed_at                DATETIME(6) NOT NULL,
    revoked_at               DATETIME(6) NULL,
    deleted_at               DATETIME(6) NULL,
    created_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- 誓約区分 enum 制約（設計書 §4 誓約区分 enum）
    CONSTRAINT chk_sc_covenant_type CHECK (covenant_type IN (
        'SUCCESSION_PRE_REGISTRATION',
        'PRIVACY_CONSENT',
        'MONITORING_CONSENT'
    ))
);

-- クロスドメイン弱参照（FK なし・INDEX のみ）
CREATE INDEX idx_sc_org ON succession_covenants (organization_id, deleted_at);
CREATE INDEX idx_sc_dwelling ON succession_covenants (dwelling_unit_id);
CREATE INDEX idx_sc_resident_type ON succession_covenants (resident_registry_id, covenant_type);
CREATE INDEX idx_sc_signer ON succession_covenants (signer_user_id, covenant_type, signed_at DESC);
