-- F03.x: スケジュールメディアアップロード管理テーブル（画像・動画両対応）
CREATE TABLE schedule_media_uploads (
    id                 BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    schedule_id        BIGINT UNSIGNED  NULL,
    uploader_id        BIGINT UNSIGNED  NULL,
    media_type         VARCHAR(10)      NOT NULL DEFAULT 'IMAGE',
    r2_key             VARCHAR(500)     NOT NULL,
    thumbnail_r2_key   VARCHAR(500)     NULL,
    file_name          VARCHAR(255)     NOT NULL,
    file_size          BIGINT           NOT NULL,
    content_type       VARCHAR(50)      NOT NULL,
    duration_seconds   INT              NULL,
    caption            VARCHAR(500)     NULL,
    taken_at           DATETIME         NULL,
    is_cover           BOOLEAN          NOT NULL DEFAULT FALSE,
    is_expense_receipt BOOLEAN          NOT NULL DEFAULT FALSE,
    processing_status  VARCHAR(20)      NOT NULL DEFAULT 'READY',
    created_at         DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_smu_r2_key (r2_key),
    INDEX idx_smu_schedule (schedule_id),
    INDEX idx_smu_uploader (uploader_id),
    INDEX idx_smu_cover (schedule_id, is_cover),
    INDEX idx_smu_processing (schedule_id, processing_status),

    -- schedule_media_uploads は schedule ドメイン内のテーブルなので FK 制約あり
    CONSTRAINT fk_smu_schedule FOREIGN KEY (schedule_id) REFERENCES schedules (id) ON DELETE SET NULL
    -- uploader_id → users はクロスドメイン参照のため FK 制約なし（インデックスのみ）

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='スケジュールメディアアップロード（画像・動画）管理';
