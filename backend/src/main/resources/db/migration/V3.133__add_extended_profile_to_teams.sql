-- F01.2: teams テーブルに拡張プロフィールカラム追加
ALTER TABLE teams
    ADD COLUMN homepage_url VARCHAR(512) DEFAULT NULL COMMENT 'ホームページURL（http/https のみ）',
    ADD COLUMN established_date DATE DEFAULT NULL COMMENT '設立年月日（日不明時は01で埋める）',
    ADD COLUMN established_date_precision ENUM('YEAR', 'YEAR_MONTH', 'FULL') DEFAULT NULL COMMENT '設立日精度',
    ADD COLUMN philosophy TEXT DEFAULT NULL COMMENT 'チーム理念・フィロソフィー（最大2000文字）',
    ADD COLUMN profile_visibility JSON DEFAULT NULL COMMENT 'プロフィール項目ごとの公開可否フラグ';
