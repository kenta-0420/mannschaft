CREATE TABLE shift_swap_requests (
    id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    slot_id         BIGINT UNSIGNED  NOT NULL,
    requester_id    BIGINT UNSIGNED  NOT NULL,
    accepter_id     BIGINT UNSIGNED,
    status          VARCHAR(20)      NOT NULL DEFAULT 'PENDING',
    reason          VARCHAR(500),
    admin_note      VARCHAR(500),
    resolved_by     BIGINT UNSIGNED,
    resolved_at     DATETIME,
    created_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_ssw_slot (slot_id),
    INDEX idx_ssw_requester_status (requester_id, status),

    CONSTRAINT fk_ssw_slot FOREIGN KEY (slot_id) REFERENCES shift_slots (id) ON DELETE CASCADE,
    CONSTRAINT fk_ssw_requester FOREIGN KEY (requester_id) REFERENCES users (id),
    CONSTRAINT fk_ssw_accepter FOREIGN KEY (accepter_id) REFERENCES users (id),
    CONSTRAINT fk_ssw_resolved_by FOREIGN KEY (resolved_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='シフト交代リクエスト';
