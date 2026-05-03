ALTER TABLE shift_requests
    ADD COLUMN is_proxy_input  TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '代理入力フラグ（0=本人, 1=代理）'
        AFTER note,
    ADD COLUMN proxy_input_record_id BIGINT UNSIGNED NULL
        COMMENT '代理入力記録ID（proxy_input_records.id）'
        AFTER is_proxy_input,
    ADD INDEX idx_shift_requests_proxy (is_proxy_input),
    ADD CONSTRAINT fk_shift_requests_proxy
        FOREIGN KEY (proxy_input_record_id)
        REFERENCES proxy_input_records (id)
        ON DELETE SET NULL;
