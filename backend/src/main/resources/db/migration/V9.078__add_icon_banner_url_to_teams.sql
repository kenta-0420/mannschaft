ALTER TABLE teams
    ADD COLUMN icon_url   VARCHAR(500) NULL COMMENT 'アイコン画像R2キー' AFTER name,
    ADD COLUMN banner_url VARCHAR(500) NULL COMMENT 'バナー画像R2キー'   AFTER icon_url;
