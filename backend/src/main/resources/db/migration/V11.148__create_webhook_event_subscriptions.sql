-- Webhookイベントサブスクリプションテーブル
CREATE TABLE webhook_event_subscriptions (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    endpoint_id BIGINT UNSIGNED NOT NULL,
    event_type  VARCHAR(100) NOT NULL,
    created_at  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_wes_endpoint FOREIGN KEY (endpoint_id) REFERENCES webhook_endpoints (id),
    UNIQUE INDEX uq_wes_endpoint_event (endpoint_id, event_type),
    INDEX idx_wes_event_type (event_type)
);
