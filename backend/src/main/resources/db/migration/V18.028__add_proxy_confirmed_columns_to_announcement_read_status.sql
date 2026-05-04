ALTER TABLE announcement_read_status
    ADD COLUMN is_proxy_confirmed    TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '代理確認フラグ（0=本人既読, 1=代理確認）'
        AFTER read_at,
    ADD COLUMN proxy_input_record_id BIGINT UNSIGNED NULL
        COMMENT '代理入力記録ID（proxy_input_records.id）'
        AFTER is_proxy_confirmed,
    ADD INDEX idx_announcement_read_status_proxy (is_proxy_confirmed),
    ADD CONSTRAINT fk_announcement_read_status_proxy
        FOREIGN KEY (proxy_input_record_id)
        REFERENCES proxy_input_records (id)
        ON DELETE SET NULL;
