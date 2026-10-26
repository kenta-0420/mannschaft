-- デジタルサイネージ スケジュールテーブル
CREATE TABLE signage_schedules (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    screen_id   BIGINT UNSIGNED NOT NULL,
    name        VARCHAR(100) NOT NULL,
    day_of_week VARCHAR(20)  NOT NULL COMMENT 'WEEKDAY/WEEKEND/EVERYDAY/MON/TUE/WED/THU/FRI/SAT/SUN',
    start_time  TIME         NOT NULL,
    end_time    TIME         NOT NULL,
    slot_ids    JSON         NOT NULL COMMENT '表示するslot_idの配列',
    priority    INT          NOT NULL DEFAULT 0 COMMENT '数値が大きいほど優先',
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_sch_screen FOREIGN KEY (screen_id) REFERENCES signage_screens (id) ON DELETE CASCADE,
    INDEX idx_sch_screen (screen_id)
);
