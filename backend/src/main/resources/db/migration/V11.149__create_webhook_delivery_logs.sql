-- Webhook配信ログテーブル
CREATE TABLE webhook_delivery_logs (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    endpoint_id      BIGINT       NOT NULL,
    event_type       VARCHAR(100) NOT NULL,
    event_id         VARCHAR(36)  NOT NULL COMMENT 'UUID v4 冪等性キー',
    request_payload  MEDIUMTEXT   NULL,
    response_status  INT          NULL,
    delivery_status  VARCHAR(20)  NOT NULL COMMENT 'SUCCESS/FAILED/RETRYING',
    retry_count      INT          NOT NULL DEFAULT 0,
    error_message    TEXT         NULL,
    created_at       DATETIME     NOT NULL,
    updated_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_wdl_endpoint FOREIGN KEY (endpoint_id) REFERENCES webhook_endpoints (id),
    INDEX idx_wdl_endpoint (endpoint_id, created_at),
    INDEX idx_wdl_event_id (event_id)
);
