-- F08.9 会費受益者制限: チーム/組織ごとに「受益者は会員(MEMBER)のみ」設定を保持するテーブルを新設する。
-- デフォルト ON（beneficiary_member_only=TRUE）＝純 SUPPORTER を受益者から除外する（マスター御裁可）。
-- 主キーは UUIDv7 (BINARY(16)) — アーキ原則6に従い時刻順ソート可能 UUID。
-- team_id / organization_id はいずれも他ドメインへの参照なので FK なし、インデックスのみ（アーキ原則1）。
-- 1スコープ1行（team_id または organization_id のどちらか一方のみが非NULL）を CHECK で保証する。

CREATE TABLE payment_beneficiary_settings (
    id                       BINARY(16)   NOT NULL,
    team_id                  BIGINT       NULL,
    organization_id          BIGINT       NULL,
    beneficiary_member_only  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_payment_beneficiary_settings_team_id (team_id),
    UNIQUE KEY uq_payment_beneficiary_settings_org_id (organization_id),
    INDEX idx_payment_beneficiary_settings_team_id (team_id),
    INDEX idx_payment_beneficiary_settings_org_id (organization_id),
    -- team_id と organization_id は排他（どちらか一方のみが非NULL）。
    CONSTRAINT chk_payment_beneficiary_settings_scope_xor
        CHECK ((team_id IS NOT NULL AND organization_id IS NULL)
            OR (team_id IS NULL AND organization_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='チーム/組織ごとの会費受益者制限設定（1スコープ1行・既定は会員のみ）';
