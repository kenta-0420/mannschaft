-- F14.1 Phase 13-γ: 代理入力記録に保管期限満了日カラムを追加する。
-- STORED 生成列により、作成日から5年後が自動的に設定される。
ALTER TABLE proxy_input_records
    ADD COLUMN retention_expires_at DATE
        GENERATED ALWAYS AS (DATE(DATE_ADD(created_at, INTERVAL 5 YEAR))) STORED
        COMMENT '保管期限満了日（作成日+5年、自動計算）';

CREATE INDEX idx_proxy_input_records_retention_expires_at
    ON proxy_input_records (retention_expires_at);
