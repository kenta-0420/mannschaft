-- V9.073: F01.5 フレンドフォルダ + フォルダメンバー（多対多）
-- 自チーム視点でフレンドチームを任意のグループに分類する。
--
-- 依存テーブル: teams (V2.004), team_friends (V9.072), users (V1.001)
-- 設計書: docs/features/F01.5_team_friend_relationships.md §4.2, §4.3

-- ---------------------------------------------------------------------------
-- team_friend_folders: フレンドフォルダ本体
-- ---------------------------------------------------------------------------
CREATE TABLE team_friend_folders (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id     BIGINT UNSIGNED NOT NULL COMMENT 'FK -> teams.id（このフォルダを所有するチーム = 自チーム）',
    name        VARCHAR(50)     NOT NULL,
    description VARCHAR(300)    NULL,
    color       VARCHAR(7)      NOT NULL DEFAULT '#6B7280' COMMENT 'カラーコード (#RRGGBB)',
    is_default  BOOLEAN         NOT NULL DEFAULT FALSE,
    sort_order  INT             NOT NULL DEFAULT 0,
    deleted_at  DATETIME(6)     NULL COMMENT '論理削除日時',
    created_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_tff_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    -- 論理削除対応のチーム内フォルダ名一意性（MySQL の NULL != NULL 特性を利用）
    CONSTRAINT uq_tff_team_name UNIQUE (team_id, name, deleted_at),
    INDEX idx_tff_team_active (team_id, deleted_at, sort_order),
    INDEX idx_tff_team_sort   (team_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='フレンドフォルダ';

-- ---------------------------------------------------------------------------
-- team_friend_folder_members: フォルダ所属関係（多対多）
-- ---------------------------------------------------------------------------
CREATE TABLE team_friend_folder_members (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    folder_id      BIGINT UNSIGNED NOT NULL,
    team_friend_id BIGINT UNSIGNED NOT NULL,
    added_by       BIGINT UNSIGNED NULL COMMENT 'FK -> users.id（追加実行者）',
    added_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_tffm_folder_friend UNIQUE (folder_id, team_friend_id),
    CONSTRAINT fk_tffm_folder   FOREIGN KEY (folder_id)      REFERENCES team_friend_folders (id) ON DELETE CASCADE,
    CONSTRAINT fk_tffm_friend   FOREIGN KEY (team_friend_id) REFERENCES team_friends (id)        ON DELETE CASCADE,
    CONSTRAINT fk_tffm_added_by FOREIGN KEY (added_by)       REFERENCES users (id)               ON DELETE SET NULL,
    INDEX idx_tffm_folder   (folder_id),
    INDEX idx_tffm_friend   (team_friend_id),
    INDEX idx_tffm_added_by (added_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='フレンドフォルダメンバー（多対多）';
