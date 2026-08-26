-- F03.16 予定コメントスレッド: schedule_comments 新設
-- 設計書: docs/features/F03.16_schedule_comment_thread.md §3.3 / §3.6
--
-- 採用方針:
--   - 主キーは UUIDv7（原則6）。id は BINARY(16)。UuidV7Entity が採番するため AUTO_INCREMENT は付けない。
--   - schedule_id / parent_id / root_id は同一ドメイン内（schedule_comments 自身・親 schedules）のため
--     FK を張る（原則2・自己参照 FK の前例: V69.001__create_village_categories.sql）。
--   - user_id はクロスドメイン（users）のため FK を張らない（原則1）。退会匿名化で NULL 化されうる。
--   - 照合順序は utf8mb4_0900_ai_ci（SchemaCollationPolicy.UNIFIED_COLLATION・
--     MigrationCollationDeclarationGuardTest が major 175 以降で強制）。
--   - @SQLRestriction は付けない（設計書 §3.3 の裁定。トゥームストーン表示が一覧の本流のため）。
--     各リポジトリメソッドが deleted_at を明示条件で扱う。

CREATE TABLE schedule_comments (
    id           BINARY(16)      NOT NULL COMMENT 'UUIDv7 主キー（UuidV7Entity・原則6）',
    schedule_id  BIGINT UNSIGNED NOT NULL COMMENT 'FK → schedules（同一ドメイン・CASCADE）。参照先に合わせBIGINT',
    user_id      BIGINT UNSIGNED NULL     COMMENT '投稿者。FKは張らない（原則1）。退会匿名化でNULL化されうる',
    parent_id    BINARY(16)      NULL     COMMENT '返信先コメントID（NULL=トップレベル）。自表参照のためUUID',
    root_id      BINARY(16)      NULL     COMMENT 'スレッド根のID（トップレベルはNULL）。自表参照のためUUID',
    depth        INT             NOT NULL DEFAULT 0 COMMENT '0=トップレベル / 1=返信（当面の上限）',
    body         TEXT            NOT NULL COMMENT '本文（アプリ層で1〜2000文字を検証）',
    is_edited    BOOLEAN         NOT NULL DEFAULT FALSE,
    reply_count  INT             NOT NULL DEFAULT 0 COMMENT '返信数（トップレベル行のみ意味を持つ。生存返信数）',
    created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at   DATETIME        NULL,
    PRIMARY KEY (id),
    INDEX idx_sc_schedule_created (schedule_id, created_at),
    INDEX idx_sc_root_created     (root_id, created_at),
    INDEX idx_sc_parent           (parent_id),
    INDEX idx_sc_user             (user_id),
    CONSTRAINT fk_sc_schedule FOREIGN KEY (schedule_id) REFERENCES schedules(id)        ON DELETE CASCADE,
    CONSTRAINT fk_sc_parent   FOREIGN KEY (parent_id)   REFERENCES schedule_comments(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='F03.16 予定コメントスレッド。出欠コメント（schedule_attendances.comment）とは別物';
