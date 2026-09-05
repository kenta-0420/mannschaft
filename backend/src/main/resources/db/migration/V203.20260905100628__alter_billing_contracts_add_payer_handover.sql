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
--   2) payer_user_id / handover_request_id 列を追加し段階フォールバックでバックフィル
--      （Codex検分1巡目P1-1対応。設計書§4.1/AC-1は「既存行をpayer_user_id=created_byでバックフィルし
--       NULL行を残さない」と定めるが、V150.20260710030428のブリッジ契約（TEAM PLAN・
--       hasPaidPlanブリッジ）はcreated_byがNULLで投入されており、単純な
--       `payer_user_id = created_by` だけではNULLが残る。
--       段階フォールバック（優先順）:
--         (a) created_by が非NULLならそれを採用（既存方針のまま）
--         (b) created_by がNULLの場合、当該スコープ（TEAM/ORG）の
--             「最古参ADMIN」（user_roles×roles(name='ADMIN')をteam_id/organization_idで結合し、
--             created_at昇順→id昇順で最初の1件）のuser_idを採用
--         (c) (a)(b)いずれでも決められない行が残る場合は、静かにNULLを残すより安全という判断で
--             migration自体をSIGNAL SQLSTATEでfailさせ手動対応を強制する（V196precheckと同型の作法）。
--             teams/organizationsテーブルには作成者を示す列が存在しないため
--             「契約対象スコープの作成者」フォールバックは本スキーマでは実装不能であり、
--             (b)の次は即座にfailとする）
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

-- (a) created_by が非NULLならそのまま採用。
UPDATE billing_contracts SET payer_user_id = created_by WHERE payer_user_id IS NULL;

-- (b) created_by が NULL の行は、当該スコープの最古参 ADMIN（user_roles.created_at 昇順の先頭行）で補う。
--     TEAM スコープ分。
UPDATE billing_contracts bc
JOIN (
    SELECT ranked.team_id, ranked.user_id
      FROM (
          SELECT ur.team_id, ur.user_id,
                 ROW_NUMBER() OVER (PARTITION BY ur.team_id ORDER BY ur.created_at ASC, ur.id ASC) AS rn
            FROM user_roles ur
            JOIN roles r ON r.id = ur.role_id
           WHERE r.name = 'ADMIN' AND ur.team_id IS NOT NULL
      ) ranked
     WHERE ranked.rn = 1
) oldest_team_admin ON bc.scope_kind = 'TEAM' AND bc.scope_id = oldest_team_admin.team_id
   SET bc.payer_user_id = oldest_team_admin.user_id
 WHERE bc.payer_user_id IS NULL;

--     ORG スコープ分（現状 V150 ブリッジは TEAM のみ生成するが、将来 created_by=NULL の ORG 行が
--     生じた場合にも同じ規則で救済できるよう対称に用意する）。
UPDATE billing_contracts bc
JOIN (
    SELECT ranked.organization_id, ranked.user_id
      FROM (
          SELECT ur.organization_id, ur.user_id,
                 ROW_NUMBER() OVER (PARTITION BY ur.organization_id ORDER BY ur.created_at ASC, ur.id ASC) AS rn
            FROM user_roles ur
            JOIN roles r ON r.id = ur.role_id
           WHERE r.name = 'ADMIN' AND ur.organization_id IS NOT NULL
      ) ranked
     WHERE ranked.rn = 1
) oldest_org_admin ON bc.scope_kind = 'ORG' AND bc.scope_id = oldest_org_admin.organization_id
   SET bc.payer_user_id = oldest_org_admin.user_id
 WHERE bc.payer_user_id IS NULL;

-- (c) (a)(b) いずれでも決められない行が残っていれば migration 自体を fail させる
--     （静かに NULL を残すより安全。V196 の precheck プロシージャと同型の作法）。
DROP PROCEDURE IF EXISTS billing_payer_handover_v203_backfill_guard;

CREATE PROCEDURE billing_payer_handover_v203_backfill_guard()
BEGIN
    DECLARE unresolved_count BIGINT DEFAULT 0;

    SELECT COUNT(*) INTO unresolved_count
      FROM billing_contracts
     WHERE payer_user_id IS NULL;

    IF unresolved_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '柱③-B V203: payer_user_id backfill left unresolved rows '
                '(created_by NULL かつ当該スコープに ADMIN が存在しない契約が残存。手動対応が必要)';
    END IF;
END;

CALL billing_payer_handover_v203_backfill_guard();
DROP PROCEDURE billing_payer_handover_v203_backfill_guard;

CREATE INDEX idx_billing_contracts_payer ON billing_contracts (payer_user_id, scope_kind, status);

-- ---------------------------------------------------------------------
-- 3) status CHECK を 5 値 → 6 値へ拡張（PENDING_HANDOVER 追加・R2-P0-1対応）
--    CHECK 制約名はスキーマ全域一意のため、DROP → 同名 ADD で置換する（V151 と同じ作法）。
-- ---------------------------------------------------------------------
ALTER TABLE billing_contracts
    DROP CHECK chk_bc_status;
ALTER TABLE billing_contracts
    ADD CONSTRAINT chk_bc_status CHECK (status IN ('PENDING','ACTIVE','PAST_DUE','CANCELLED','EXPIRED','PENDING_HANDOVER'));
