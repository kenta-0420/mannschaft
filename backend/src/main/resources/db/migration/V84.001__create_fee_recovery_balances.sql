-- F22.1 市（Market）謝礼決済 §6.3 第一陣: ModeB 相殺自動化の基盤
-- 未回収 PLATFORM_FEE 残高を payee（connect_account 単位）で持つ。
--
-- ModeB 返金（02_api_design.md §6.1 / §6.3）では、Mannschaft が支払者へ grossRefund を満額返金し
-- `refund_application_fee:true` で application_fee を返金するため、Stripe 実手数料（grossRefund − R）を
-- Mannschaft が一時負担する。この未回収額を payee（受領者の Stripe Connect アカウント）単位で積み上げ、
-- 後続の謝礼決済で fee と相殺（自動回収＝RECOVERY 台帳種別）するための残高表。
--
-- 設計書: docs/features/F22.1_market/payment/02_api_design.md §6.1-6.3 / §6.3
-- 設計原則:
--   原則1: クロスドメイン/論理参照 — connect_accounts.id への FK は張らない（payment 内だが
--          残高表は connect_account のライフサイクルに CASCADE で巻き込まれない方が安全なため論理参照）。
--   原則6: 主キーは UUIDv7（BINARY(16)）。
--   原則7: organization_id を保持しテナント絞り込み（AbstractTenantAwareRepository 適用）。
--   deleted_at: 残高表は payee×currency で物理1行だが、AbstractTenantAwareRepository の
--          基底メソッド（findByOrganizationIdAndDeletedAtIsNull 等）が deleted_at を要求するため列を持つ。
--          連結口座の切離し（再 onboarding）時に論理削除して新残高行を立て直せる余地も確保する。
CREATE TABLE fee_recovery_balances (
    id                  BINARY(16)       NOT NULL COMMENT 'PK (UUIDv7)',
    connect_account_id  BINARY(16)       NOT NULL COMMENT 'connect_accounts.id（論理参照・FKなし）',
    organization_id     BIGINT UNSIGNED  NULL     COMMENT 'テナント絞り込み用（シャードキー候補）',
    outstanding_amount  BIGINT           NOT NULL DEFAULT 0
                            COMMENT 'ModeB 返金で一時負担した Stripe 実手数料の未回収残高（minor 単位・将来の符号反転に備え署名付き BIGINT）',
    currency            CHAR(3)          NOT NULL DEFAULT 'jpy' COMMENT '通貨（minor 単位の母数）',
    created_at          DATETIME(6)      NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)      NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at          DATETIME(6)      NULL     COMMENT '論理削除（連結口座切離し時の残高リセット用）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_frb_account_currency (connect_account_id, currency),
    INDEX idx_frb_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F22.1 ModeB 返金で Mannschaft が一時負担した Stripe 実手数料の未回収残高（§6.3 / 02_api_design.md §6.1-6.3）';
