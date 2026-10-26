ALTER TABLE users
    ADD COLUMN banner_url VARCHAR(500) NULL COMMENT 'バナー画像R2キー' AFTER avatar_url;
