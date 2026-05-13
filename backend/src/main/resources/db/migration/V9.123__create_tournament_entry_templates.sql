-- F08.7 Phase 9: tournament_entry_templates テーブル作成
-- チームごとのエントリーテンプレート（よく使うメンバー構成を保存）を管理する。
-- team_id / created_by は クロスドメイン参照のため FK なし（アプリ層で整合性保証）。
CREATE TABLE tournament_entry_templates (
    id          CHAR(36)         NOT NULL,
    team_id     BIGINT UNSIGNED  NOT NULL COMMENT 'クロスドメイン参照: teams.id（FK なし）',
    name        VARCHAR(50)      NOT NULL,
    description VARCHAR(200)     NULL,
    sort_order  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    created_by  BIGINT UNSIGNED  NOT NULL COMMENT 'クロスドメイン参照: users.id（FK なし）',
    created_at  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at  DATETIME         NULL,
    PRIMARY KEY (id),
    INDEX idx_tet_team (team_id, deleted_at, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
