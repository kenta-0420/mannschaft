ALTER TABLE organizations
    ADD COLUMN icon_url   VARCHAR(512) NULL COMMENT 'アイコン画像R2キー' AFTER name,
    ADD COLUMN banner_url VARCHAR(512) NULL COMMENT 'バナー画像R2キー'   AFTER icon_url;
