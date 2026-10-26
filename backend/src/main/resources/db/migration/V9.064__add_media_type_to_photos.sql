-- F06.2 ギャラリー: photos テーブルに動画対応カラムを追加
-- media_type カラムの追加により、写真と動画の両方を格納できるようにする
-- 設計書: docs/features/F06.2_member_gallery.md 参照
--
-- 追加カラム:
--   media_type         ... メディア種別（PHOTO / VIDEO）
--   duration_seconds   ... 動画の再生時間（秒。VIDEO のみ）
--   video_codec        ... 動画コーデック（VIDEO のみ）
--   processing_status  ... 後処理ステータス（PHOTO は即時 READY、VIDEO は PENDING → READY）
--
-- file_size は既存 INT UNSIGNED から BIGINT UNSIGNED への拡張が必要（動画対応）
-- インデックス: idx_ph_album_media を追加（メディア種別フィルタ用）

ALTER TABLE photos
    -- ファイルサイズを BIGINT に拡張（動画対応。タイムラインや他テーブルと統一）
    MODIFY COLUMN file_size BIGINT UNSIGNED NOT NULL
        COMMENT 'ファイルサイズ（bytes）。動画対応のため INT → BIGINT に拡張',

    -- メディア種別（r2_key の直後に追加）
    ADD COLUMN media_type ENUM('PHOTO', 'VIDEO') NOT NULL DEFAULT 'PHOTO'
        COMMENT 'メディア種別: PHOTO=静止画, VIDEO=動画。既存レコードはすべて PHOTO'
        AFTER r2_key,

    -- 動画の再生時間（秒）
    ADD COLUMN duration_seconds INT UNSIGNED NULL
        COMMENT '動画の再生時間（秒）。VIDEO のみ。Cloudflare Workers がメタデータ抽出時に格納'
        AFTER media_type,

    -- 動画コーデック
    ADD COLUMN video_codec VARCHAR(30) NULL
        COMMENT '動画コーデック（例: h264, h265, vp9）。VIDEO のみ'
        AFTER duration_seconds,

    -- 後処理ステータス
    ADD COLUMN processing_status ENUM('PENDING', 'PROCESSING', 'READY', 'FAILED') NOT NULL DEFAULT 'READY'
        COMMENT '後処理ステータス: PHOTO は即時 READY、VIDEO はアップロード直後 PENDING → Workers 実行中 PROCESSING → 完了で READY'
        AFTER video_codec,

    -- メディア種別フィルタ用インデックス（PHOTO のみ / VIDEO のみ表示の最適化）
    ADD INDEX idx_ph_album_media (album_id, media_type, sort_order);
