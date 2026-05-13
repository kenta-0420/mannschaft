-- F08.7 Phase 9: tournament_entry_template_members テーブル作成
-- エントリーテンプレートに含まれるメンバー（選手）の一覧を管理する。
-- user_id は users テーブルへのクロスドメイン参照のため FK なし（アプリ層で整合性保証）。
-- template_id は同一ドメイン内の tournament_entry_templates への参照のため CASCADE 許可。
CREATE TABLE tournament_entry_template_members (
    id            CHAR(36)          NOT NULL,
    template_id   CHAR(36)          NOT NULL,
    user_id       BIGINT UNSIGNED   NOT NULL COMMENT 'クロスドメイン参照: users.id（FK なし）',
    jersey_number SMALLINT UNSIGNED NULL,
    position      VARCHAR(30)       NULL,
    sort_order    SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE INDEX uq_tetm_template_user (template_id, user_id),
    INDEX idx_tetm_template (template_id, sort_order),
    INDEX idx_tetm_user (user_id),
    CONSTRAINT fk_tetm_template FOREIGN KEY (template_id)
        REFERENCES tournament_entry_templates (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
