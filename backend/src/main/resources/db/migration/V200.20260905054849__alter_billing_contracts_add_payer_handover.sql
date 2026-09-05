-- =====================================================================
-- 柱③-B 組織契約の請求担当と個人支払手段の分離（CMP-260901-1538・PR-1: DDL＋読み取り専用の土台）
-- =====================================================================
-- 設計書: docs/architecture/billing_payer_handover_design.md §4.1
-- クロスドメインFKは張らない（payer_user_id は users.id への論理参照・INDEXのみ）。
--
-- 本マイグレーションの内容:
--   1) billing_contracts.status を VARCHAR(12) → VARCHAR(20) へ拡張
--      （新設 'PENDING_HANDOVER' は16文字であり既存の12文字上限に収まらないため必須。
--       設計書のDDL案には明記が無いが、V150で列長がVARCHAR(12)固定のまま据え置かれていたため、
--       本PRで根治する＝対処療法ではなく列長そのものを是正する）
--   2) payer_user_id / handover_request_id 列を追加しバックフィル
--   3) status CHECK を 5 値 → 6 値へ拡張（PENDING_HANDOVER 追加・R2-P0-1対応）
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) status 列長拡張（PENDING_HANDOVER 追加に伴う根治・V150由来のVARCHAR(12)を是正）
-- ---------------------------------------------------------------------
ALTER TABLE billing_contracts
    MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / CANCELLED / EXPIRED / PENDING / PAST_DUE / PENDING_HANDOVER';

-- ---------------------------------------------------------------------
-- 2) payer_user_id / handover_request_id 列追加＋バックフィル
-- ---------------------------------------------------------------------
ALTER TABLE billing_contracts
    ADD COLUMN payer_user_id BIGINT UNSIGNED NULL COMMENT '現在この契約の実質決済者（Stripe Customer 紐付け先）。作成時は created_by と同値で初期化し、引継後に更新される' AFTER created_by,
    ADD COLUMN handover_request_id BINARY(16) NULL COMMENT 'PENDING_HANDOVER 中に自分を作った billing_payer_handover_requests.id（新契約行のみ非NULL）' AFTER payer_user_id;

UPDATE billing_contracts SET payer_user_id = created_by WHERE payer_user_id IS NULL;

CREATE INDEX idx_billing_contracts_payer ON billing_contracts (payer_user_id, scope_kind, status);

-- ---------------------------------------------------------------------
-- 3) status CHECK を 5 値 → 6 値へ拡張（PENDING_HANDOVER 追加・R2-P0-1対応）
--    CHECK 制約名はスキーマ全域一意のため、DROP → 同名 ADD で置換する（V151 と同じ作法）。
-- ---------------------------------------------------------------------
ALTER TABLE billing_contracts
    DROP CHECK chk_bc_status;
ALTER TABLE billing_contracts
    ADD CONSTRAINT chk_bc_status CHECK (status IN ('PENDING','ACTIVE','PAST_DUE','CANCELLED','EXPIRED','PENDING_HANDOVER'));
