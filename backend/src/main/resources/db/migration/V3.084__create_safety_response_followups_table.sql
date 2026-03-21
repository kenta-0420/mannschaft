CREATE TABLE safety_response_followups (
    id                  BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    safety_response_id  BIGINT UNSIGNED  NOT NULL,
    followup_status     VARCHAR(20)      NOT NULL DEFAULT 'PENDING',
    assigned_to         BIGINT UNSIGNED,
    note                VARCHAR(500),
    created_at          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_srf_response (safety_response_id),

    CONSTRAINT fk_srf_response FOREIGN KEY (safety_response_id) REFERENCES safety_responses (id) ON DELETE CASCADE,
    CONSTRAINT fk_srf_assigned_to FOREIGN KEY (assigned_to) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='安否確認フォローアップ';
