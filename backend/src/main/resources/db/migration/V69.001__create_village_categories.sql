-- 村カテゴリ階層マスタ（F17.1 村作成フォーム カテゴリ選択）
-- グローバルマスタ（全テナント共通）。villages.category は VARCHAR で文字列参照するため FK なし。
-- 主キーは UUIDv7 (CLAUDE.md 原則6)。自己参照 FK は同一ドメイン内のため許可 (原則2)。

CREATE TABLE village_categories (
    id           BINARY(16)   NOT NULL         COMMENT 'UUIDv7 主キー',
    name         VARCHAR(64)  NOT NULL         COMMENT 'カテゴリ名',
    parent_id    BINARY(16)   NULL             COMMENT '親カテゴリID（NULL = ルート）',
    display_order INT         NOT NULL DEFAULT 0 COMMENT '表示順（10刻み推奨）',
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    deleted_at   DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_village_categories_parent
        FOREIGN KEY (parent_id) REFERENCES village_categories(id),
    INDEX idx_village_categories_parent_id (parent_id),
    INDEX idx_village_categories_display_order (display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='村カテゴリ階層マスタ';
