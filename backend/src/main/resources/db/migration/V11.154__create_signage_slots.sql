-- デジタルサイネージ スロットテーブル
CREATE TABLE signage_slots (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    screen_id      BIGINT       NOT NULL,
    slot_order     INT          NOT NULL,
    slot_type      VARCHAR(30)  NOT NULL COMMENT 'ANNOUNCEMENT/SCHEDULE/ACTIVITY_HIGHLIGHT/RANKING/FREE_TEXT/FREE_IMAGE/WEATHER/CLOCK',
    title          VARCHAR(200) NULL,
    slide_duration INT          NULL COMMENT 'NULLの場合はscreen.default_slide_durationを使用',
    content_config JSON         NULL COMMENT 'スロット固有の設定',
    is_active      TINYINT(1)   NOT NULL DEFAULT 1,
    created_at     DATETIME     NOT NULL,
    updated_at     DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_sl_screen FOREIGN KEY (screen_id) REFERENCES signage_screens (id) ON DELETE CASCADE,
    UNIQUE INDEX uq_slot_screen_order (screen_id, slot_order),
    INDEX idx_sl_screen (screen_id, is_active, slot_order)
);
