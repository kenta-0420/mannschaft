-- F22.1 市（Market）: フレンド宛非公開札の宛先テーブル（01_data_model §4）
-- visibility='FRIEND_TEAMS_ONLY' の札の宛先を 3 粒度（全体 / フォルダ / 個別チーム）で記録する。
--
-- 依存テーブル: recruitment_listings (V3.119)
-- 主キー: BINARY(16)（UUIDv7・UuidV7Entity 継承。CLAUDE.md 原則 6）
-- FK 方針:
--   listing_id → recruitment_listings(id) は同一ドメイン（recruitment）なので CASCADE 可（原則 2）
--   folder_id（F01.5）/ team_id（team ドメイン）はクロスドメインのため FK なし・index のみ（原則 1）
-- 雛形参照: V9.073__create_team_friend_folders_table.sql

CREATE TABLE recruitment_friend_targets (
    id          BINARY(16)      NOT NULL COMMENT 'PK（UUIDv7）',
    listing_id  BIGINT UNSIGNED NOT NULL COMMENT 'FK -> recruitment_listings.id（同一ドメイン CASCADE）',
    target_kind VARCHAR(20)     NOT NULL COMMENT '宛先粒度: ALL_FRIENDS / FOLDER / TEAM',
    folder_id   BIGINT UNSIGNED NULL     COMMENT 'F01.5 フレンドフォルダID（target_kind=FOLDER のとき必須・FKなし）',
    team_id     BIGINT UNSIGNED NULL     COMMENT '宛先チームID（target_kind=TEAM のとき必須・FKなし）',
    created_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_rft_listing
        FOREIGN KEY (listing_id) REFERENCES recruitment_listings (id) ON DELETE CASCADE,
    -- target_kind と参照列の整合（Service 層 + CHECK で二重保証）
    CONSTRAINT ck_rft_kind CHECK (
        (target_kind = 'ALL_FRIENDS' AND folder_id IS NULL     AND team_id IS NULL) OR
        (target_kind = 'FOLDER'      AND folder_id IS NOT NULL  AND team_id IS NULL) OR
        (target_kind = 'TEAM'        AND team_id   IS NOT NULL  AND folder_id IS NULL)
    ),
    -- 同一宛先の重複登録防止
    CONSTRAINT uk_rft_listing_kind_ref UNIQUE (listing_id, target_kind, folder_id, team_id),
    INDEX idx_rft_listing (listing_id),
    INDEX idx_rft_team    (team_id),
    INDEX idx_rft_folder  (folder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F22.1 市: フレンド宛非公開札の宛先（3粒度）';
