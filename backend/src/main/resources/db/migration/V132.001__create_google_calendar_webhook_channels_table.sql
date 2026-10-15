CREATE TABLE google_calendar_webhook_channels (
    id               BINARY(16)    NOT NULL,
    user_id          BIGINT        NOT NULL,
    channel_id       VARCHAR(255)  NOT NULL,
    resource_id      VARCHAR(255)  NOT NULL,
    channel_token    VARCHAR(64)   NOT NULL,
    expires_at       DATETIME(3)   NOT NULL,
    last_received_at DATETIME(3)   NULL,
    created_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_gcwc            PRIMARY KEY (id),
    CONSTRAINT uq_gcwc_user_id    UNIQUE (user_id),
    CONSTRAINT uq_gcwc_channel_id UNIQUE (channel_id),
    INDEX idx_gcwc_expires_at (expires_at)
);
