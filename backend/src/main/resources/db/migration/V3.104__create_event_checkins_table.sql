-- F03.8 イベント管理: チェックインテーブル
CREATE TABLE event_checkins (
    id                  BIGINT UNSIGNED          NOT NULL AUTO_INCREMENT,
    event_id            BIGINT UNSIGNED          NOT NULL,
    ticket_id           BIGINT UNSIGNED          NOT NULL,
    checkin_type        VARCHAR(20)     NOT NULL DEFAULT 'STAFF_SCAN',
    checked_in_by       BIGINT UNSIGNED,
    checked_in_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    note                VARCHAR(300),
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_event_checkins_ticket (ticket_id),
    CONSTRAINT fk_event_checkins_event
        FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE,
    CONSTRAINT fk_event_checkins_ticket
        FOREIGN KEY (ticket_id) REFERENCES event_tickets (id) ON DELETE CASCADE,
    CONSTRAINT fk_event_checkins_checked_by
        FOREIGN KEY (checked_in_by) REFERENCES users (id) ON DELETE SET NULL,
    INDEX idx_ec_event_checkin_at (event_id, checked_in_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='イベントチェックイン';
