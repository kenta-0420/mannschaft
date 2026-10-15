-- F06.1 CMS ブログ: blog_image_uploads テーブルを blog_media_uploads にリネーム
-- 旧名 blog_image_uploads は画像専用を示唆していたが、動画対応に合わせてリネームする
-- 設計書: docs/features/F06.1_cms_blog.md 参照
RENAME TABLE blog_image_uploads TO blog_media_uploads;
