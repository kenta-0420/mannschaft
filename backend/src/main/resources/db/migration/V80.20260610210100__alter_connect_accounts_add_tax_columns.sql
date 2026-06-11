-- F08.9 P8: 税からくり用列追加（connect_accounts）
-- 適格請求書番号・税ステータスは将来の税務確定後に埋める
ALTER TABLE connect_accounts
    ADD COLUMN tax_registration_number VARCHAR(20) NULL COMMENT '適格請求書登録番号（インボイス制度）' AFTER payouts_enabled,
    ADD COLUMN tax_status VARCHAR(20) NULL COMMENT '税務ステータス（PENDING/REGISTERED/EXEMPT）' AFTER tax_registration_number;
