-- =====================================================================
-- F17 村機能 Phase 2 : 村代表委任テーブル新設
--
-- 概要:
--   Phase 1 では「チーム/組織 ADMIN は自動的に代表」運用としていたが、
--   Phase 2 で個別委任テーブル village_representatives を追加し、
--   HEADMAN が任意のメンバーへ代表権を委譲できるようにする。
--
-- 参照: docs/features/F17.1_village_community.md §3.11, §5.4
--
-- 設計原則の遵守:
--   - 原則1: クロスドメインFK禁止
--     → representative_user_id / granted_by_user_id / revoked_by_user_id は
--        users への FK を張らない（インデックスのみ）
--   - 原則2: CASCADE は同一ドメイン内のみ
--     → village_id, village_membership_id は同一 village ドメイン → CASCADE 可
--   - 原則6: UUIDv7 PK (BINARY(16))
--   - 原則7 適用外: 全テナント横断ドメインのため organization_id を持たず、
--                    AbstractTenantAwareRepository も非継承
--
-- 注記:
--   - villages.monsho_r2_key は V9.125 で既に作成済みのため追加不要
-- =====================================================================

CREATE TABLE village_representatives (
    id                     BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    village_id             BINARY(16)      NOT NULL                                COMMENT 'FK → villages.id（村全体検索のためのキャッシュ）',
    membership_id          BINARY(16)      NOT NULL                                COMMENT 'FK → village_memberships.id（TEAM/ORG メンバーシップ）',
    representative_user_id BIGINT UNSIGNED NOT NULL                                COMMENT '代表権を委任されたユーザーID（FK 張らない・原則1）',
    granted_by_user_id     BIGINT UNSIGNED NOT NULL                                COMMENT '委任を実行した HEADMAN ユーザーID（FK 張らない・原則1）',
    granted_at             DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)   COMMENT '委任日時',
    revoked_at             DATETIME(6)     NULL                                    COMMENT '委任取消し日時（論理削除）',
    revoked_by_user_id     BIGINT UNSIGNED NULL                                    COMMENT '取消し実行ユーザーID（FK 張らない・原則1）',
    note                   VARCHAR(200)    NULL                                    COMMENT '委任理由メモ',
    created_at             DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                BIGINT          NOT NULL DEFAULT 0                      COMMENT '楽観的ロック',
    PRIMARY KEY (id),
    UNIQUE KEY uk_vr_active (membership_id, representative_user_id, revoked_at)
        COMMENT '退任（revoked_at IS NOT NULL）は履歴並存可、現役（NULL）は組合せ1件のみ',
    KEY idx_vr_village (village_id, revoked_at),
    KEY idx_vr_user (representative_user_id, revoked_at),
    CONSTRAINT fk_vr_village FOREIGN KEY (village_id)
        REFERENCES villages(id) ON DELETE CASCADE,
    CONSTRAINT fk_vr_membership FOREIGN KEY (membership_id)
        REFERENCES village_memberships(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='F17 Phase 2: 村代表ロール委任';
