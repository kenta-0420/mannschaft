-- F06.1 CMS ブログ: blog_media_uploads テーブルに動画対応カラムを追加
-- 既存の blog_image_uploads（V6.006）→ blog_media_uploads（V9.065 でリネーム済み）に対して
-- 動画アップロード追跡・孤立ファイルクリーンアップ用の動画関連カラムを追加する
-- 設計書: docs/features/F06.1_cms_blog.md 参照
--
-- 追加カラム:
--   media_type         ... メディア種別（IMAGE / VIDEO）
--   thumbnail_r2_key   ... 動画のポスターフレームサムネイル R2 オブジェクトキー（VIDEO のみ）
--   duration_seconds   ... 動画再生時間（秒。VIDEO のみ）
--   processing_status  ... 後処理ステータス（IMAGE は即時 READY、VIDEO は PENDING → READY）
--
-- file_size は既存 INT UNSIGNED から BIGINT UNSIGNED への拡張が必要（動画対応）
-- s3_key カラムは設計書の記載通り「歴史的経緯で維持」のため今回はリネームしない

ALTER TABLE blog_media_uploads
    -- ファイルサイズを BIGINT に拡張（動画対応。他テーブルと統一）
    MODIFY COLUMN file_size BIGINT UNSIGNED NOT NULL
        COMMENT 'ファイルサイズ（bytes）。動画対応のため INT → BIGINT に拡張',

    -- メディア種別（s3_key の直前に追加）
    ADD COLUMN media_type ENUM('IMAGE', 'VIDEO') NOT NULL DEFAULT 'IMAGE'
        COMMENT 'メディア種別: IMAGE=静止画, VIDEO=動画。既存レコードはすべて IMAGE'
        AFTER uploader_id,

    -- 動画のポスターフレームサムネイル R2 オブジェクトキー
    ADD COLUMN thumbnail_r2_key VARCHAR(500) NULL
        COMMENT '動画のポスターフレームサムネイル R2 オブジェクトキー（VIDEO のみ）。ハイブリッド方式で生成'
        AFTER s3_key,

    -- 動画再生時間
    ADD COLUMN duration_seconds INT UNSIGNED NULL
        COMMENT '動画再生時間（秒。VIDEO のみ）。5 分（300 秒）を境にサムネイル生成経路を Workers / クライアントで振り分ける'
        AFTER thumbnail_r2_key,

    -- 後処理ステータス
    ADD COLUMN processing_status ENUM('PENDING', 'PROCESSING', 'READY', 'FAILED') NOT NULL DEFAULT 'READY'
        COMMENT '後処理ステータス: IMAGE は即時 READY、VIDEO はアップロード直後 PENDING → Workers 実行中 PROCESSING → 完了で READY'
        AFTER duration_seconds;
