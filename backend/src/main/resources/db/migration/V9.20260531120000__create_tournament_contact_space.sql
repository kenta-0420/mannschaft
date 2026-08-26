-- F08.7.1 / 01 連絡機能: 大会・ディビジョン単位の連絡スペース管理テーブル。
--
-- 「このスコープ（大会 or ディビジョン）の、この種別（掲示板 or チャット）のスペースが、
--  どの bulletin/chat リソースに払い出されているか」と公開フラグを 1 テーブルで管理する。
--
-- 設計書: docs/features/F08.7.1_tournament_extensions/01_communication.md §2.1
--
-- 原則準拠:
--   - 主キーは UUIDv7（原則 6・UuidV7Entity 継承）。id は BINARY(16)。
--   - クロスドメイン FK は張らない（原則 1）。scope_id / ref_id は ID 値のみ保持し、
--     参照整合性はアプリ層で保証する。
--   - organization_id は持たない（大会 ID から組織を辿れるため・設計書 §2.1 備考）。
CREATE TABLE tournament_contact_space (
    id          BINARY(16)       NOT NULL COMMENT 'UUIDv7（原則6）',
    scope_type  VARCHAR(30)      NOT NULL COMMENT 'TOURNAMENT / TOURNAMENT_DIVISION',
    scope_id    BIGINT UNSIGNED  NOT NULL COMMENT 'tournaments.id または tournament_divisions.id（FK なし・原則1）',
    space_kind  VARCHAR(20)      NOT NULL COMMENT 'BULLETIN / CHAT',
    ref_id      BIGINT UNSIGNED  NOT NULL COMMENT '払い出した実体の ID（BULLETIN=bulletin_categories.id / CHAT=chat_channels.id）。FK なし',
    is_public   BOOLEAN          NOT NULL DEFAULT FALSE COMMENT '公開トグル（TRUE で PUBLIC 閲覧可。CHAT も既定 FALSE）',
    created_at  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at  DATETIME         NULL COMMENT '論理削除（大会/ディビジョン削除時に archive）',

    PRIMARY KEY (id),
    -- 冪等化（同一スコープ×種別で 1 つ）＋「このスコープのリソース id」逆引き
    UNIQUE KEY uq_tcs_scope_kind (scope_type, scope_id, space_kind),
    -- ref_id 逆引き（chat_channel id → どの大会スペースか）
    KEY idx_tcs_ref (space_kind, ref_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F08.7.1 大会・ディビジョン連絡スペース';
