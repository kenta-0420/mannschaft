-- F06.2 ギャラリー: photos テーブルのカラム名を R2 命名規約に統一
-- s3_key → r2_key, thumbnail_s3_key → thumbnail_r2_key
-- 既存の S3 時代のカラム名を Cloudflare R2 に対応した命名規約へ変更する
ALTER TABLE photos
    RENAME COLUMN s3_key TO r2_key,
    RENAME COLUMN thumbnail_s3_key TO thumbnail_r2_key;
