-- F11.4 外観テーマ設定: ダークモード用背景色カラムを追加する。
-- bg_color（ライトモード用）の直後に dark_bg_color を追加する。
-- デフォルト値 '#18181b' は shadcn-ui の zinc-950 相当の暗色系標準背景色。
-- NOT NULL DEFAULT 付きの ALTER は既存行も即座にデフォルト値で埋まる（MySQL 8.0 Instant DDL）。

ALTER TABLE appearance_settings
    ADD COLUMN dark_bg_color VARCHAR(32) NOT NULL DEFAULT '#18181b' AFTER bg_color;
