-- F03.8 イベント管理: チケットテーブル
CREATE TABLE event_tickets (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    registration_id     BIGINT          NOT NULL,
    event_id            BIGINT          NOT NULL,
    ticket_type_id      BIGINT          NOT NULL,
    qr_token            CHAR(36)        NOT NULL,
    ticket_number       VARCHAR(30)     NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'VALID',
    used_at             DATETIME,
    cancelled_at        DATETIME,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_event_tickets_qr (qr_token),
    UNIQUE KEY uq_event_tickets_number (ticket_number),
    CONSTRAINT fk_event_tickets_registration
        FOREIGN KEY (registration_id) REFERENCES event_registrations (id) ON DELETE CASCADE,
    CONSTRAINT fk_event_tickets_event
        FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE,
    CONSTRAINT fk_event_tickets_ticket_type
        FOREIGN KEY (ticket_type_id) REFERENCES event_ticket_types (id) ON DELETE RESTRICT,
    INDEX idx_et_event_status (event_id, status),
    INDEX idx_et_registration (registration_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='イベントチケット';
