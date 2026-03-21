-- F03.8 イベント管理: 参加登録テーブル
CREATE TABLE event_registrations (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    event_id            BIGINT          NOT NULL,
    user_id             BIGINT,
    ticket_type_id      BIGINT          NOT NULL,
    guest_name          VARCHAR(100),
    guest_email         VARCHAR(255),
    guest_phone         VARCHAR(50),
    status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    quantity            INT UNSIGNED    NOT NULL DEFAULT 1,
    note                VARCHAR(500),
    approved_by         BIGINT,
    approved_at         DATETIME,
    cancelled_at        DATETIME,
    cancel_reason       VARCHAR(500),
    invite_token_id     BIGINT,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_er_user_event (user_id, event_id),
    CONSTRAINT fk_event_registrations_event
        FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE,
    CONSTRAINT fk_event_registrations_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_event_registrations_ticket_type
        FOREIGN KEY (ticket_type_id) REFERENCES event_ticket_types (id) ON DELETE RESTRICT,
    CONSTRAINT fk_event_registrations_approved_by
        FOREIGN KEY (approved_by) REFERENCES users (id) ON DELETE SET NULL,
    INDEX idx_er_event_status (event_id, status, created_at DESC),
    INDEX idx_er_user_event (user_id, event_id),
    INDEX idx_er_guest_email (guest_email, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='イベント参加登録';
