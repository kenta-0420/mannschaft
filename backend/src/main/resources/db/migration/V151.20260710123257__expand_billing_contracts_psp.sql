-- =====================================================================
-- F20.1 課金・エンタイトルメント基盤: billing_contracts へ PSP（Stripe）列を前倒し追加
-- =====================================================================
-- 設計書: docs/features/F20.1_entitlement_billing/01_data_model.md §3.1（D-1 PSP 列前倒し）
--   実決済（Stripe 自社受取・月額サブスク）に対応するため、契約行に PSP 参照列を追加する。
--   クロスドメインFKは張らない（psp_* は Stripe 側 ID の論理参照）。UUIDv7 主キーは変更しない。
--
-- マスター御裁可（2026-07-10）:
--   D-1 PSP 列前倒し / D-2 Mode.SUBSCRIPTION・Connect不使用・webhook逆引きで F08.9 と分離 /
--   D-3 無償解約=即時失効・有償解約=期末解約 / D-4 価格入力後の新規契約のみ決済必須。
--
-- 既存データ番人:
--   既存 billing_contracts 行は status が全て {ACTIVE,CANCELLED,EXPIRED} の3値内であり、
--   本マイグレーションは列追加＋CHECK 拡張のみ（UPDATE 不要・拡張は既存値を包含）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) PSP 参照列を追加（Stripe Customer / Subscription / 現サイクル終了）
-- ---------------------------------------------------------------------
ALTER TABLE billing_contracts
    ADD COLUMN psp_customer_ref     VARCHAR(64) NULL COMMENT 'Stripe Customer ID（cus_xxx・論理参照）。決済フローの契約のみ',
    ADD COLUMN psp_subscription_ref VARCHAR(64) NULL COMMENT 'Stripe Subscription ID（sub_xxx・論理参照）。webhook 逆引きキー',
    ADD COLUMN current_period_end   DATETIME(6) NULL COMMENT '現サイクル終了（valid_until 上限／期末解約の失効時刻）';

-- ---------------------------------------------------------------------
-- 2) webhook 逆引き用 UNIQUE（psp_subscription_ref は NULL 複数可・非決済契約は NULL）
--    MySQL の UNIQUE は NULL を重複扱いしないため、無償契約（NULL）が複数あっても衝突しない。
-- ---------------------------------------------------------------------
ALTER TABLE billing_contracts
    ADD UNIQUE KEY uk_bc_psp_subscription (psp_subscription_ref);

-- ---------------------------------------------------------------------
-- 3) status CHECK を 5 値へ拡張（PENDING / PAST_DUE を追加）
--    PENDING  : 決済フローで Checkout 生成済み・入金前（entitlements 未発行）
--    PAST_DUE : 継続課金の支払失敗（current_period_end まで利用可・権利は触らない）
--    CHECK 制約名はスキーマ全域一意のため、DROP → 同名 ADD で置換する。
-- ---------------------------------------------------------------------
ALTER TABLE billing_contracts
    DROP CHECK chk_bc_status;
ALTER TABLE billing_contracts
    ADD CONSTRAINT chk_bc_status CHECK (status IN ('PENDING','ACTIVE','PAST_DUE','CANCELLED','EXPIRED'));
