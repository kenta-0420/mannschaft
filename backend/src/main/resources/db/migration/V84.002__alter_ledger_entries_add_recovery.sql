-- F22.1 市（Market）謝礼決済 §6.3 第一陣: ledger_entries.entry_type に RECOVERY を追加
--
-- ModeB 返金で Mannschaft が一時負担した Stripe 実手数料を、後続決済の fee と相殺して
-- 自動回収（RECOVERY）した事実を複式記帳台帳に追記するための記帳種別。
--
-- 既知の作法（feedback_flyway_existing_data_check_drop）:
--   既存 CHECK 制約 chk_le_entry_type（V72.006 で 6 値を許可）を DROP してから、
--   既存6値＋RECOVERY の 7 値セットで再作成する。値の追加のみゆえ既存行の UPDATE は不要だが、
--   旧 CHECK を残すと新値 RECOVERY の INSERT が CHECK 違反でクラッシュするため必ず DROP→ADD する。
--
-- 設計書: docs/features/F22.1_market/payment/01_data_model.md §3.3 / 02_api_design.md §6.3
ALTER TABLE ledger_entries
    DROP CONSTRAINT chk_le_entry_type;

ALTER TABLE ledger_entries
    ADD CONSTRAINT chk_le_entry_type CHECK (entry_type IN
        ('AUTHORIZE','CAPTURE','TRANSFER_OUT','FEE','REFUND','CANCEL','RECOVERY'));
