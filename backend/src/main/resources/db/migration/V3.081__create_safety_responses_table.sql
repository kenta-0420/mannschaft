CREATE TABLE safety_responses (
    id                BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    safety_check_id   BIGINT UNSIGNED  NOT NULL,
    user_id           BIGINT UNSIGNED  NOT NULL,
    status            VARCHAR(20)      NOT NULL,
    message           VARCHAR(200),
    message_source    VARCHAR(10),
    gps_shared        BOOLEAN          NOT NULL DEFAULT FALSE,
    gps_latitude      DECIMAL(10, 7),
    gps_longitude     DECIMAL(10, 7),
    responded_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at        DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_sr_check_user (safety_check_id, user_id),

    CONSTRAINT fk_safety_resp_check FOREIGN KEY (safety_check_id) REFERENCES safety_checks (id) ON DELETE CASCADE,
    CONSTRAINT fk_safety_resp_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='安否確認回答';
