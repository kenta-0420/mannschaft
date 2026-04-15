-- V9.073: F01.5 フレンドフォルダ + フォルダメンバー（多対多）
-- 自チーム視点でフレンドチームを任意のグループに分類する。
--
-- 依存テーブル: teams (V2.004), team_friends (V9.072)
-- 設計書: docs/features/F01.5_team_friend_relationships.md §4.2, §4.3

-- ---------------------------------------------------------------------------
-- team_friend_folders: フレンドフォルダ本体
-- ---------------------------------------------------------------------------
CREATE TABLE team_friend_folders (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    owner_team_id BIGINT UNSIGNED NOT NULL COMMENT 'FK -> teams.id（自チーム視点）',
    name          VARCHAR(50)     NOT NULL,
    description   VARCHAR(200)    NULL,
    color         VARCHAR(7)      NOT NULL DEFAULT '#808080' COMMENT 'カラーコード (#RRGGBB)',
    is_default    BOOLEAN         NOT NULL DEFAULT FALSE,
    folder_order  INT             NOT NULL DEFAULT 0,
    deleted_at    DATETIME(6)     NULL COMMENT '論理削除日時',
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_tff_owner_team FOREIGN KEY (owner_team_id) REFERENCES teams (id) ON DELETE CASCADE,
    INDEX idx_tff_owner_active (owner_team_id, deleted_at, folder_order),
    INDEX idx_tff_owner_name   (owner_team_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='フレンドフォルダ';

-- ---------------------------------------------------------------------------
-- team_friend_folder_members: フォルダ所属関係（多対多）
-- ---------------------------------------------------------------------------
CREATE TABLE team_friend_folder_members (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    folder_id      BIGINT UNSIGNED NOT NULL,
    team_friend_id BIGINT UNSIGNED NOT NULL,
    added_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_tffm_pair   UNIQUE (folder_id, team_friend_id),
    CONSTRAINT fk_tffm_folder FOREIGN KEY (folder_id)      REFERENCES team_friend_folders (id) ON DELETE CASCADE,
    CONSTRAINT fk_tffm_friend FOREIGN KEY (team_friend_id) REFERENCES team_friends (id)        ON DELETE CASCADE,
    INDEX idx_tffm_folder (folder_id),
    INDEX idx_tffm_friend (team_friend_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='フレンドフォルダメンバー（多対多）';
