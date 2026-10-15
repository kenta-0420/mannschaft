-- F04.3 ハイブリッド方式: ユーザー単位のグローバル通知設定テーブルを新設する。
--
-- 1ユーザー1行（UNIQUE KEY uq_ns_user）。レコードなし = 既定値（priority_auto_delivery=TRUE）。
-- 現状は「優先度による自動配信」のみだが、将来のグローバル設定（おやすみモード等）の置き場。
--
-- 主キーは UUIDv7（BINARY(16)）— アーキ原則6に従い時刻順ソート可能UUID。
-- user_id は auth ドメインへの参照なのでクロスドメインFKなし（アーキ原則1）。
--   UNIQUE KEY が user_id の BTree インデックスを兼ねる。
--
-- ⚠️ 採番注意: V123 で作成。マージ前に origin/main 全体の最大 major + 1 へリネームが必要。

CREATE TABLE notification_settings (
    id                      BINARY(16)      NOT NULL,
    user_id                 BIGINT UNSIGNED NOT NULL,
    priority_auto_delivery  BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    -- UNIQUE KEY が user_id の BTree インデックスを兼ねる（別途 INDEX は重複のため張らない）。
    UNIQUE KEY uq_ns_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='ユーザー単位のグローバル通知設定（1ユーザー1行・優先度による自動配信）';
