-- CMP-260909-2152 α: 消化行の締めイベントの印から締めイベントへの FK（同一ドメイン・RESTRICT）。
ALTER TABLE shift_budget_consumptions
    ADD KEY idx_sbc_close_event (close_event_id),
    ADD CONSTRAINT fk_sbc_close_event FOREIGN KEY (close_event_id) REFERENCES shift_budget_monthly_close_events (id) ON DELETE RESTRICT;
