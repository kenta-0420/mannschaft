import type { components } from '~/types/generated'

/**
 * F20.1 課金・エンタイトルメント基盤の API 呼び出し。
 *
 * 型は生成型（openapi-typescript）を最優先で使う（memory `feedback_fe_api_type_assertion_field_lie`）。
 * 契約作成 API は Idempotency-Key ヘッダ必須（設計書 02 §0 M-1）。
 *
 * BE の team/org スコープ Long パスパラメータは、既存の他ドメイン composable
 * （useBudgetApi・useReservationApi 等）と同様に slug 文字列をそのまま渡す運用に揃える。
 */

// === 生成型（真実のソース = openapi-typescript）===
export type BillingPlanCatalogResponse = components['schemas']['BillingPlanCatalogResponse']
export type BillingPlanItem = components['schemas']['BillingPlanItem']
export type BillingFeatureItem = components['schemas']['BillingFeatureItem']
export type BillingPriceBand = components['schemas']['BillingPriceBand']
export type BillingEntitlementSummaryResponse = components['schemas']['BillingEntitlementSummaryResponse']
export type BillingActiveContract = components['schemas']['BillingActiveContract']
export type BillingEntitledFeature = components['schemas']['BillingEntitledFeature']
export type BillingEntitlementCheckResponse = components['schemas']['BillingEntitlementCheckResponse']
export type BillingContractResponse = components['schemas']['BillingContractResponse']
export type BillingCreateContractRequest = components['schemas']['BillingCreateContractRequest']
export type BillingChangePlanRequest = components['schemas']['BillingChangePlanRequest']
export type BillingPlanAdminResponse = components['schemas']['BillingPlanAdminResponse']
export type BillingPlanUpsertRequest = components['schemas']['BillingPlanUpsertRequest']
export type BillingFeatureAdminResponse = components['schemas']['BillingFeatureAdminResponse']
export type BillingFeatureUpsertRequest = components['schemas']['BillingFeatureUpsertRequest']
export type BillingPriceBandInput = components['schemas']['BillingPriceBandInput']
export type BillingPriceBandsReplaceRequest = components['schemas']['BillingPriceBandsReplaceRequest']
export type BillingPlanFeaturesReplaceRequest = components['schemas']['BillingPlanFeaturesReplaceRequest']
export type BillingManualGrantRequest = components['schemas']['BillingManualGrantRequest']
export type BillingPagedContractResponse = components['schemas']['BillingPagedContractResponse']

/** API 表現のスコープ種別（設計書 02 §0）。 */
export type BillingScopeKind = 'USER' | 'TEAM' | 'ORG'

/** 契約作成に必須の Idempotency-Key ヘッダを生成する（連打・再送の二重発行防止・設計書 02 §0 M-1）。 */
function idempotencyHeaders(): Record<string, string> {
  return { 'Idempotency-Key': crypto.randomUUID() }
}

export function useBillingApi() {
  const api = useApi()

  // ============================================================
  // カタログ・権利判定（認証ユーザー共通）
  // ============================================================

  /** プランカタログ（enabled のプラン・機能を sort_order 昇順）。 */
  async function getPlanCatalog() {
    return api<{ data: BillingPlanCatalogResponse }>('/api/v1/billing/plans')
  }

  /** 単一機能の判定（FE の表示出し分け専用。BE ゲートが正）。 */
  async function checkEntitlement(scopeKind: BillingScopeKind, scopeId: number, featureKey: string) {
    const query = new URLSearchParams({ scopeKind, scopeId: String(scopeId), featureKey })
    return api<{ data: BillingEntitlementCheckResponse }>(`/api/v1/billing/entitlements/check?${query.toString()}`)
  }

  // ============================================================
  // 権利サマリ（現在の契約・有効機能）
  // ============================================================

  async function getMyEntitlements() {
    return api<{ data: BillingEntitlementSummaryResponse }>('/api/v1/me/entitlements')
  }

  async function getTeamEntitlements(teamId: string) {
    return api<{ data: BillingEntitlementSummaryResponse }>(`/api/v1/teams/${teamId}/entitlements`)
  }

  async function getOrgEntitlements(orgId: string) {
    return api<{ data: BillingEntitlementSummaryResponse }>(`/api/v1/organizations/${orgId}/entitlements`)
  }

  /** スコープ種別に応じて権利サマリ取得を振り分ける。 */
  async function getEntitlements(scopeKind: BillingScopeKind, scopeId: string) {
    if (scopeKind === 'USER') return getMyEntitlements()
    if (scopeKind === 'TEAM') return getTeamEntitlements(scopeId)
    return getOrgEntitlements(scopeId)
  }

  // ============================================================
  // 契約作成（Idempotency-Key 必須）
  // ============================================================

  async function createMyContract(body: BillingCreateContractRequest) {
    return api<{ data: BillingContractResponse }>('/api/v1/me/billing/contracts', {
      method: 'POST',
      body,
      headers: idempotencyHeaders(),
    })
  }

  async function createTeamContract(teamId: string, body: BillingCreateContractRequest) {
    return api<{ data: BillingContractResponse }>(`/api/v1/teams/${teamId}/billing/contracts`, {
      method: 'POST',
      body,
      headers: idempotencyHeaders(),
    })
  }

  async function createOrgContract(orgId: string, body: BillingCreateContractRequest) {
    return api<{ data: BillingContractResponse }>(`/api/v1/organizations/${orgId}/billing/contracts`, {
      method: 'POST',
      body,
      headers: idempotencyHeaders(),
    })
  }

  /** スコープ種別に応じて契約作成を振り分ける。 */
  async function createContract(scopeKind: BillingScopeKind, scopeId: string, body: BillingCreateContractRequest) {
    if (scopeKind === 'USER') return createMyContract(body)
    if (scopeKind === 'TEAM') return createTeamContract(scopeId, body)
    return createOrgContract(scopeId, body)
  }

  // ============================================================
  // 解約
  // ============================================================

  async function cancelMyContract(contractId: string) {
    return api<{ data: BillingContractResponse }>(`/api/v1/me/billing/contracts/${contractId}`, { method: 'DELETE' })
  }

  async function cancelTeamContract(teamId: string, contractId: string) {
    return api<{ data: BillingContractResponse }>(`/api/v1/teams/${teamId}/billing/contracts/${contractId}`, { method: 'DELETE' })
  }

  async function cancelOrgContract(orgId: string, contractId: string) {
    return api<{ data: BillingContractResponse }>(`/api/v1/organizations/${orgId}/billing/contracts/${contractId}`, { method: 'DELETE' })
  }

  async function cancelContract(scopeKind: BillingScopeKind, scopeId: string, contractId: string) {
    if (scopeKind === 'USER') return cancelMyContract(contractId)
    if (scopeKind === 'TEAM') return cancelTeamContract(scopeId, contractId)
    return cancelOrgContract(scopeId, contractId)
  }

  // ============================================================
  // プラン変更
  // ============================================================

  async function changeMyPlan(contractId: string, body: BillingChangePlanRequest) {
    return api<{ data: BillingContractResponse }>(`/api/v1/me/billing/contracts/${contractId}`, { method: 'PUT', body })
  }

  async function changeTeamPlan(teamId: string, contractId: string, body: BillingChangePlanRequest) {
    return api<{ data: BillingContractResponse }>(`/api/v1/teams/${teamId}/billing/contracts/${contractId}`, { method: 'PUT', body })
  }

  async function changeOrgPlan(orgId: string, contractId: string, body: BillingChangePlanRequest) {
    return api<{ data: BillingContractResponse }>(`/api/v1/organizations/${orgId}/billing/contracts/${contractId}`, { method: 'PUT', body })
  }

  async function changePlan(scopeKind: BillingScopeKind, scopeId: string, contractId: string, body: BillingChangePlanRequest) {
    if (scopeKind === 'USER') return changeMyPlan(contractId, body)
    if (scopeKind === 'TEAM') return changeTeamPlan(scopeId, contractId, body)
    return changeOrgPlan(scopeId, contractId, body)
  }

  // ============================================================
  // シスアド運用 API（マスタ CRUD・手動付与・契約横断検索）
  // ============================================================

  const ADMIN_BASE = '/api/v1/system-admin/billing'

  async function listPlansAdmin() {
    return api<{ data: BillingPlanAdminResponse[] }>(`${ADMIN_BASE}/plans`)
  }

  async function getPlanAdmin(planKey: string) {
    return api<{ data: BillingPlanAdminResponse }>(`${ADMIN_BASE}/plans/${planKey}`)
  }

  async function createPlanAdmin(planKey: string, body: BillingPlanUpsertRequest) {
    return api<{ data: BillingPlanAdminResponse }>(`${ADMIN_BASE}/plans/${planKey}`, { method: 'POST', body })
  }

  async function updatePlanAdmin(planKey: string, body: BillingPlanUpsertRequest) {
    return api<{ data: BillingPlanAdminResponse }>(`${ADMIN_BASE}/plans/${planKey}`, { method: 'PUT', body })
  }

  async function deletePlanAdmin(planKey: string) {
    return api<unknown>(`${ADMIN_BASE}/plans/${planKey}`, { method: 'DELETE' })
  }

  async function replacePriceBandsAdmin(planKey: string, body: BillingPriceBandsReplaceRequest) {
    return api<unknown>(`${ADMIN_BASE}/plans/${planKey}/price-bands`, { method: 'PUT', body })
  }

  async function replacePlanFeaturesAdmin(planKey: string, body: BillingPlanFeaturesReplaceRequest) {
    return api<unknown>(`${ADMIN_BASE}/plans/${planKey}/features`, { method: 'PUT', body })
  }

  async function listFeaturesAdmin() {
    return api<{ data: BillingFeatureAdminResponse[] }>(`${ADMIN_BASE}/features`)
  }

  async function getFeatureAdmin(featureKey: string) {
    return api<{ data: BillingFeatureAdminResponse }>(`${ADMIN_BASE}/features/${featureKey}`)
  }

  async function createFeatureAdmin(featureKey: string, body: BillingFeatureUpsertRequest) {
    return api<{ data: BillingFeatureAdminResponse }>(`${ADMIN_BASE}/features/${featureKey}`, { method: 'POST', body })
  }

  async function updateFeatureAdmin(featureKey: string, body: BillingFeatureUpsertRequest) {
    return api<{ data: BillingFeatureAdminResponse }>(`${ADMIN_BASE}/features/${featureKey}`, { method: 'PUT', body })
  }

  async function deleteFeatureAdmin(featureKey: string) {
    return api<unknown>(`${ADMIN_BASE}/features/${featureKey}`, { method: 'DELETE' })
  }

  async function grantAdmin(body: BillingManualGrantRequest) {
    return api<{ data: BillingContractResponse }>(`${ADMIN_BASE}/grants`, { method: 'POST', body })
  }

  async function searchContractsAdmin(params?: { scopeKind?: string; scopeId?: number; status?: string; page?: number; size?: number }) {
    const query = new URLSearchParams()
    if (params?.scopeKind) query.set('scopeKind', params.scopeKind)
    if (params?.scopeId != null) query.set('scopeId', String(params.scopeId))
    if (params?.status) query.set('status', params.status)
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: BillingPagedContractResponse }>(`${ADMIN_BASE}/contracts?${query.toString()}`)
  }

  return {
    getPlanCatalog,
    checkEntitlement,
    getMyEntitlements,
    getTeamEntitlements,
    getOrgEntitlements,
    getEntitlements,
    createMyContract,
    createTeamContract,
    createOrgContract,
    createContract,
    cancelMyContract,
    cancelTeamContract,
    cancelOrgContract,
    cancelContract,
    changeMyPlan,
    changeTeamPlan,
    changeOrgPlan,
    changePlan,
    listPlansAdmin,
    getPlanAdmin,
    createPlanAdmin,
    updatePlanAdmin,
    deletePlanAdmin,
    replacePriceBandsAdmin,
    replacePlanFeaturesAdmin,
    listFeaturesAdmin,
    getFeatureAdmin,
    createFeatureAdmin,
    updateFeatureAdmin,
    deleteFeatureAdmin,
    grantAdmin,
    searchContractsAdmin,
  }
}
