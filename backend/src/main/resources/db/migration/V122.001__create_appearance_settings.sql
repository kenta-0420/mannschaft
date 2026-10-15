-- F11.4 外観テーマ設定: ユーザーごとのテーマ・背景色設定テーブルを新設する。
-- 主キーは UUIDv7 (BINARY(16)) — アーキ原則6に従い時刻順ソート可能UUID。
-- user_id は auth ドメインへの参照なのでFKなし、インデックスのみ（アーキ原則1）。
-- 1ユーザー1行を UNIQUE KEY で保証し、upsert で複数端末の設定を同期する。
--
-- ⚠️ 採番注意: このファイルは V122 で作成したが、マージ時に origin/main 全体の
-- 最大 major + 1 にリネームが必要。PR マージ前に必ず確認すること。

CREATE TABLE appearance_settings (
    id                  BINARY(16)      NOT NULL,
    user_id             BIGINT          NOT NULL,
    theme               VARCHAR(8)      NOT NULL DEFAULT 'LIGHT',
    bg_color            VARCHAR(32)     NOT NULL DEFAULT '#f3efe0',
    seasonal_theme_id   BIGINT          NULL,
    hide_chat_preview   BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at          DATETIME(6)     NOT NULL,
    updated_at          DATETIME(6)     NOT NULL,

    PRIMARY KEY (id),
    -- UNIQUE KEY が user_id の BTree インデックスを兼ねる（別途 INDEX は重複のため張らない）。
    UNIQUE KEY uq_appearance_settings_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='ユーザーごとの外観テーマ設定（1ユーザー1行・複数端末同期）';
