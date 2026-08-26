-- F08.9 P8: 税からくり用列追加（payment_items）
-- 値は将来の国別TaxPolicy実装まで埋めない（nullable）
ALTER TABLE payment_items
    ADD COLUMN tax_category VARCHAR(30) NULL COMMENT '税区分（例: STANDARD_10 / REDUCED_8 / EXEMPT）' AFTER term_ends_on,
    ADD COLUMN tax_rate DECIMAL(5,4) NULL COMMENT '税率（0.1000=10%）' AFTER tax_category,
    ADD COLUMN price_includes_tax BOOLEAN NULL DEFAULT FALSE COMMENT '税込み価格フラグ' AFTER tax_rate;
