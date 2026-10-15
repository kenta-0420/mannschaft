-- F03.8 イベント管理: タイムテーブル項目テーブル
CREATE TABLE event_timetable_items (
    id                  BIGINT UNSIGNED          NOT NULL AUTO_INCREMENT,
    event_id            BIGINT UNSIGNED          NOT NULL,
    title               VARCHAR(200)    NOT NULL,
    description         VARCHAR(500),
    speaker             VARCHAR(100),
    start_at            DATETIME,
    end_at              DATETIME,
    location            VARCHAR(200),
    sort_order          INT             NOT NULL DEFAULT 0,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_event_timetable_items_event
        FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE,
    INDEX idx_eti_event_start_sort (event_id, start_at, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='イベントタイムテーブル項目';
