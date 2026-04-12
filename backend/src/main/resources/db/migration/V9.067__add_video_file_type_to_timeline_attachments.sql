-- F04.1 タイムライン: timeline_post_attachments テーブルに VIDEO_FILE 型対応カラムを追加
-- 設計書: docs/features/F04.1_timeline.md 参照
--
-- 既存テーブルの attachment_type は VARCHAR(20) のため、
-- ENUM への変更は行わず VARCHAR の値として VIDEO_FILE をアプリ層で扱う
--
-- 追加カラム:
--   video_thumbnail_key     ... VIDEO_FILE のサムネイル画像の R2 オブジェクトキー（Workers 非同期生成）
--   video_duration_seconds  ... 動画の再生時間（秒。VIDEO_FILE のみ）
--   video_codec             ... 動画コーデック（VIDEO_FILE のみ）
--   video_width             ... 動画解像度 幅（px。VIDEO_FILE のみ）
--   video_height            ... 動画解像度 高さ（px。VIDEO_FILE のみ）
--   video_processing_status ... VIDEO_FILE の後処理ステータス
--
-- file_size は既存 INT から BIGINT UNSIGNED への拡張が必要（動画対応。他テーブルと統一）
-- image_width / image_height は SMALLINT → SMALLINT UNSIGNED へ変更（px は負数不要）
--
-- 注意: attachment_type の値は VARCHAR(20) のままアプリ層で管理する
--   有効値: IMAGE / VIDEO_LINK / LINK_PREVIEW / VIDEO_FILE
--   VARCHAR から ENUM へのマイグレーションは将来タスクとして別途検討

ALTER TABLE timeline_post_attachments
    -- attachment_type カラムを拡張（VARCHAR のまま維持、長さを拡張して VIDEO_FILE に対応）
    -- 設計書の ENUM 定義と整合するよう CHECK 制約を追加
    MODIFY COLUMN attachment_type VARCHAR(20) NOT NULL
        COMMENT '添付種別: IMAGE / VIDEO_FILE / VIDEO_LINK / LINK_PREVIEW',

    -- ファイルサイズを BIGINT UNSIGNED に拡張（動画対応）
    MODIFY COLUMN file_size BIGINT UNSIGNED NULL
        COMMENT 'ファイルサイズ（bytes）。IMAGE / VIDEO_FILE の場合。動画対応のため INT → BIGINT UNSIGNED に拡張',

    -- 画像幅を SMALLINT UNSIGNED に変更（px は負数不要）
    MODIFY COLUMN image_width SMALLINT UNSIGNED NULL
        COMMENT '画像幅（px）。IMAGE の場合',

    -- 画像高さを SMALLINT UNSIGNED に変更
    MODIFY COLUMN image_height SMALLINT UNSIGNED NULL
        COMMENT '画像高さ（px）。IMAGE の場合',

    -- VIDEO_FILE のサムネイル画像 R2 オブジェクトキー（Workers 非同期生成）
    ADD COLUMN video_thumbnail_key VARCHAR(500) NULL
        COMMENT 'VIDEO_FILE のサムネイル画像の R2 オブジェクトキー（Cloudflare Workers が非同期生成）'
        AFTER video_thumbnail_url,

    -- 動画の再生時間（秒）
    ADD COLUMN video_duration_seconds INT UNSIGNED NULL
        COMMENT '動画の再生時間（秒）。VIDEO_FILE のみ。Workers がメタデータ解析時に格納'
        AFTER video_thumbnail_key,

    -- 動画コーデック
    ADD COLUMN video_codec VARCHAR(30) NULL
        COMMENT '動画コーデック（例: h264, h265, vp9）。VIDEO_FILE のみ'
        AFTER video_duration_seconds,

    -- 動画解像度 幅
    ADD COLUMN video_width SMALLINT UNSIGNED NULL
        COMMENT '動画解像度 幅（px）。VIDEO_FILE のみ'
        AFTER video_codec,

    -- 動画解像度 高さ
    ADD COLUMN video_height SMALLINT UNSIGNED NULL
        COMMENT '動画解像度 高さ（px）。VIDEO_FILE のみ'
        AFTER video_width,

    -- VIDEO_FILE の後処理ステータス
    ADD COLUMN video_processing_status ENUM('PENDING', 'PROCESSING', 'READY', 'FAILED') NULL
        COMMENT 'VIDEO_FILE の後処理ステータス: アップロード直後 PENDING → Workers 実行中 PROCESSING → 完了で READY。FAILED 時はフロントで再生不可表示。IMAGE / VIDEO_LINK / LINK_PREVIEW の場合は NULL'
        AFTER video_height,

    -- 投稿別添付ファイル取得用インデックス（設計書の定義に合わせて追加）
    ADD INDEX idx_tpa_post (timeline_post_id, sort_order);
