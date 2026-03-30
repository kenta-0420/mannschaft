-- デジタルサイネージ 緊急メッセージテーブル
CREATE TABLE signage_emergency_messages (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    screen_id        BIGINT       NOT NULL,
    message          VARCHAR(500) NOT NULL,
    background_color VARCHAR(7)   NOT NULL DEFAULT '#FF0000',
    text_color       VARCHAR(7)   NOT NULL DEFAULT '#FFFFFF',
    is_active        TINYINT(1)   NOT NULL DEFAULT 0,
    sent_by          BIGINT       NOT NULL,
    dismissed_at     DATETIME     NULL,
    dismissed_by     BIGINT       NULL,
    created_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_sem_screen       FOREIGN KEY (screen_id)    REFERENCES signage_screens (id) ON DELETE CASCADE,
    CONSTRAINT fk_sem_sent_by      FOREIGN KEY (sent_by)      REFERENCES users (id),
    CONSTRAINT fk_sem_dismissed_by FOREIGN KEY (dismissed_by) REFERENCES users (id),
    INDEX idx_sem_screen_active (screen_id, is_active)
);
