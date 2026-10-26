-- F08.7.1 / 07 大会費用支払い: 大会参加費（payment_item と 大会/ディビジョンを結ぶ薄い連結）。
--
-- 既存 F08.2 決済基盤（payment_items / member_payments / stripe_customers / Stripe Checkout＋MANUAL /
-- team_access_requirements / content_payment_gates / grace_period / webhook / 返金 REFUNDED/CANCELLED）を
-- そのまま再利用する。本マイグレーションは「大会参加費を payment_item として主催組織に紐付け、
-- どの大会/ディビジョンに、誰を対象に、いつまでに支払うか」を表す薄い連結テーブルのみを新設する。
-- 金額・通貨・Stripe Product/Price・grace_period は payment_items 側で一元管理し、本テーブルは持たない。
--
-- 設計書: docs/features/F08.7.1_tournament_extensions/07_tournament_payment.md §2
--
-- 原則準拠:
--   - 主キーは UUIDv7（原則6・UuidV7Entity 継承）。id は BINARY(16)。
--   - クロスドメイン FK は張らない（原則1）。payment_item_id / tournament_id / division_id / team_id は
--     ID 値のみ保持し、参照整合性はアプリ層で保証する。
--   - 子テーブル tournament_fee_target は同一ドメイン（fee の子）のため CASCADE 削除を許可する（原則2）。
--   - 論理削除（deleted_at）で履歴を保持し、クロスドメイン CASCADE は使わない（原則2・3）。

CREATE TABLE tournament_fee (
    id              BINARY(16)      NOT NULL COMMENT 'UUIDv7（原則6）',
    tournament_id   BIGINT          NOT NULL COMMENT '対象大会（tournaments.id・FK なし／原則1）',
    division_id     BIGINT          NULL     COMMENT '対象ディビジョン（tournament_divisions.id。NULL=大会全体・FK なし）',
    payment_item_id BIGINT          NOT NULL COMMENT 'payment ドメインの payment_items.id（FK なし／原則1）',
    title           VARCHAR(255)    NOT NULL COMMENT '表示名（例「2026 春季リーグ 参加費」）',
    target_scope    VARCHAR(20)     NOT NULL DEFAULT 'ALL_TEAMS' COMMENT '対象＝全参加チーム(ALL_TEAMS) / 特定チーム(SPECIFIC_TEAMS)',
    payment_due     DATETIME        NULL     COMMENT '支払期限（NULL=期限なし）。grace_period は payment_item 側',
    organization_id BIGINT          NOT NULL COMMENT '主催組織（入金先・テナント絞り込み）',
    created_by      BIGINT          NOT NULL COMMENT '作成した主催組織 ADMIN の user_id（退会時も履歴保持）',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME        NULL     COMMENT '論理削除（履歴保持・クロスドメイン CASCADE なし）',

    PRIMARY KEY (id),
    KEY idx_tournament_fee_tournament (tournament_id, division_id),
    KEY idx_tournament_fee_org (organization_id),
    KEY idx_tournament_fee_payment_item (payment_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.7.1/07 大会参加費（payment_item と大会/ディビジョンの薄い連結）';

CREATE TABLE tournament_fee_target (
    id         BINARY(16) NOT NULL COMMENT 'UUIDv7（原則6）',
    fee_id     BINARY(16) NOT NULL COMMENT '親 tournament_fee.id（同一ドメイン）',
    team_id    BIGINT     NOT NULL COMMENT '対象チーム（teams.id・FK なし／原則1）',
    created_at DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_tournament_fee_target_fee (fee_id),
    -- 同一スコープ内のチーム重複防止
    UNIQUE KEY uq_tournament_fee_target (fee_id, team_id),
    -- 同一ドメインの親子のため CASCADE 削除を許可（原則2）
    CONSTRAINT fk_tournament_fee_target_fee FOREIGN KEY (fee_id)
        REFERENCES tournament_fee (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.7.1/07 大会参加費の対象チーム明細（SPECIFIC_TEAMS 用）';
