-- F01.2: organizations テーブルに拡張プロフィールカラム追加
ALTER TABLE organizations
    ADD COLUMN homepage_url VARCHAR(512) DEFAULT NULL COMMENT 'ホームページURL（http/https のみ）' AFTER banner_url,
    ADD COLUMN established_date DATE DEFAULT NULL COMMENT '設立年月日（日不明時は01で埋める）' AFTER homepage_url,
    ADD COLUMN established_date_precision ENUM('YEAR', 'YEAR_MONTH', 'FULL') DEFAULT NULL COMMENT '設立日精度' AFTER established_date,
    ADD COLUMN philosophy TEXT DEFAULT NULL COMMENT '組織理念・フィロソフィー（最大2000文字）' AFTER established_date_precision,
    ADD COLUMN profile_visibility JSON DEFAULT NULL COMMENT 'プロフィール項目ごとの公開可否フラグ' AFTER philosophy;
