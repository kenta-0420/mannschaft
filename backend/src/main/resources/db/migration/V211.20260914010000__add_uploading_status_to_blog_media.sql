-- Presigned single PUT の完了確認前を、動画変換待ち PENDING と区別する。
-- 既存 ENUM の内部番号を変えないため UPLOADING は末尾へ追加する。
ALTER TABLE blog_media_uploads
    MODIFY COLUMN processing_status
        ENUM('PENDING', 'PROCESSING', 'READY', 'FAILED', 'UPLOADING')
        NOT NULL DEFAULT 'READY';
