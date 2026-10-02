-- =====================================================================
-- F20.1 課金・エンタイトルメント基盤: hasPaidPlan ブリッジ（Migrate 段）
-- =====================================================================
-- 設計書: docs/features/F20.1_entitlement_billing/01_data_model.md §5・README §4.1
-- 既存 team_subscriptions の ACTIVE×有料行を billing_contracts / entitlements へブリッジする。
-- PlanType{MODULE, PACKAGE, ORGANIZATION} はいずれも FULL へ写像（ベータ中の実害なし・暫定）。
-- 開発中・本番データなしのため対象0件想定だが、冪等な migration として用意する（NOT EXISTS ガード）。
--
-- 【設計書の擬似SQLからの拡張】設計書の擬似コードは billing_contracts / entitlements の2段のみだが、
-- §3.1.1（H-1）の不変条件「アクティブ PLAN 契約は active_contract_pointers に必ずポインタを持つ」を
-- 満たさないと、ブリッジ後にアプリ層の契約作成APIが「既存の有効契約なし」と誤認し二重契約を
-- 許してしまう。そのため本 migration は billing_contracts → active_contract_pointers →
-- entitlements の3段で構成する。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) ブリッジ契約行（billing_contracts）
--    対象 = team_subscriptions WHERE status='ACTIVE' AND plan_type <> 'FREE'
-- ---------------------------------------------------------------------
INSERT INTO billing_contracts
    (id, scope_kind, scope_id, organization_id, contract_kind, plan_key, feature_key,
     status, contracted_at, created_by, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()), 'TEAM', ts.team_id, NULL, 'PLAN', 'FULL', NULL,
       'ACTIVE', ts.created_at, NULL, NOW(6), NOW(6)
FROM team_subscriptions ts
WHERE ts.status = 'ACTIVE' AND ts.plan_type <> 'FREE'
  AND NOT EXISTS (
      SELECT 1 FROM billing_contracts bc
      WHERE bc.scope_kind = 'TEAM' AND bc.scope_id = ts.team_id
        AND bc.contract_kind = 'PLAN' AND bc.plan_key = 'FULL' AND bc.status = 'ACTIVE'
  );

-- ---------------------------------------------------------------------
-- 2) アクティブ契約ポインタ（active_contract_pointers）
--    §3.1.1 の H-1 不変条件（アクティブ PLAN 契約は 1 スコープ 1 本）を
--    ブリッジ後も維持するため、上記で作成した契約行にポインタを張る。
-- ---------------------------------------------------------------------
INSERT INTO active_contract_pointers
    (id, scope_kind, scope_id, contract_kind, addon_feature_key, contract_id, organization_id, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()), bc.scope_kind, bc.scope_id, bc.contract_kind, '', bc.id, bc.organization_id, NOW(6), NOW(6)
FROM billing_contracts bc
WHERE bc.contract_kind = 'PLAN' AND bc.plan_key = 'FULL' AND bc.scope_kind = 'TEAM' AND bc.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM active_contract_pointers acp
      WHERE acp.scope_kind = bc.scope_kind AND acp.scope_id = bc.scope_id
        AND acp.contract_kind = bc.contract_kind AND acp.addon_feature_key = ''
  );

-- ---------------------------------------------------------------------
-- 3) entitlements: 上記契約行 × plan_features('FULL') を展開して発行
--    valid_from=契約時刻・valid_until=NULL（無期限）
-- ---------------------------------------------------------------------
INSERT INTO entitlements
    (id, scope_kind, scope_id, feature_key, source_kind, source_ref_id,
     valid_from, valid_until, revoked_at, revoked_by, organization_id, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()), bc.scope_kind, bc.scope_id, pf.feature_key, 'PLAN', bc.id,
       bc.contracted_at, NULL, NULL, NULL, bc.organization_id, NOW(6), NOW(6)
FROM billing_contracts bc
JOIN plan_features pf ON pf.plan_key = bc.plan_key
WHERE bc.contract_kind = 'PLAN' AND bc.plan_key = 'FULL' AND bc.scope_kind = 'TEAM' AND bc.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM entitlements e
      WHERE e.scope_kind = bc.scope_kind AND e.scope_id = bc.scope_id
        AND e.feature_key = pf.feature_key AND e.source_kind = 'PLAN' AND e.source_ref_id = bc.id
  );
