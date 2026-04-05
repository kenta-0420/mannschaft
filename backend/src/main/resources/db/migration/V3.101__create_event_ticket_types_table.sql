-- F03.8 イベント管理: チケット種別テーブル
CREATE TABLE event_ticket_types (
    id                      BIGINT UNSIGNED          NOT NULL AUTO_INCREMENT,
    event_id                BIGINT UNSIGNED          NOT NULL,
    name                    VARCHAR(100)    NOT NULL,
    description             VARCHAR(500),
    price                   DECIMAL(12,0)   NOT NULL DEFAULT 0,
    currency                CHAR(3)         NOT NULL DEFAULT 'JPY',
    max_quantity            INT UNSIGNED,
    issued_count            INT UNSIGNED    NOT NULL DEFAULT 0,
    min_registration_role   VARCHAR(30)     NOT NULL DEFAULT 'MEMBER_PLUS',
    is_active               BOOLEAN         NOT NULL DEFAULT TRUE,
    sort_order              INT             NOT NULL DEFAULT 0,
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_event_ticket_types_event
        FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE,
    INDEX idx_ett_event_active_sort (event_id, is_active, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='イベントチケット種別';
