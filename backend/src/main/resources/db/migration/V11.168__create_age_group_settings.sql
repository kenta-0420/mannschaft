-- F01.9 年齢確認・保護者同意機能: 年齢グループ設定マスタテーブルを作成
-- 年齢グループごとの機能制限・テーマ設定を管理するマスタテーブル（マスタ例外: 自然キー使用）
CREATE TABLE age_group_settings (
  age_group        VARCHAR(30)     NOT NULL,
  display_name     VARCHAR(50)     NOT NULL,
  min_age          TINYINT         NOT NULL,
  max_age          TINYINT         NULL,
  features_enabled JSON            NOT NULL DEFAULT ('{}'),
  theme_config     JSON            NOT NULL DEFAULT ('{}'),
  updated_by       BIGINT UNSIGNED NULL,
  updated_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (age_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
