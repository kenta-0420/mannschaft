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
export type BillingCancelRequest = components['schemas']['BillingCancelRequest']
export type BillingContractCancelResponse = components['schemas']['BillingContractCancelResponse']

/** API 表現のスコープ種別（設計書 02 §0）。 */
export type BillingScopeKind = 'USER' | 'TEAM' | 'ORG'

// === 価格改定（price-revisions）: openapi.json 未再生成のため手動定義（段階的に生成型へ移行） ===
export type PriceRevisionProductKind = 'PLAN' | 'ADDON'
export type PriceRevisionTaxBehavior = 'INCLUSIVE' | 'EXCLUSIVE'
export type PriceRevisionStatus =
  | 'DRAFT' | 'PROVISIONING' | 'PROVISION_FAILED' | 'READY' | 'SCHEDULED' | 'ACTIVE' | 'RETIRED' | 'CANCELLED'

export interface PriceRevisionBandInput {
  bandNo: number
  minMembers: number
  maxMembers?: number | null
  inputAmount: number
  taxBehavior: PriceRevisionTaxBehavior
  taxCode: string
}

export interface PriceRevisionCreateRequest {
  productKind: PriceRevisionProductKind
  productKey: string
  scopeKind: BillingScopeKind
  effectiveFrom: string
  effectiveUntil?: string | null
  bands: PriceRevisionBandInput[]
}

export interface PriceRevisionBandResponse {
  id: string
  bandNo: number
  minMembers: number
  maxMembers?: number | null
  inputAmount: number
  taxBehavior: PriceRevisionTaxBehavior
  taxCode: string
  amountExcludingTax: number
  taxAmount: number
  amountIncludingTax: number
  taxRateBasisPoints: number
  status: PriceRevisionStatus
  stripePriceRef?: string | null
  provisionErrorCode?: string | null
  provisionAttempts: number
}

export interface PriceRevisionResponse {
  id: string
  productKind: PriceRevisionProductKind
  productKey: string
  scopeKind: BillingScopeKind
  revisionNo: number
  catalogRevision: string
  status: PriceRevisionStatus
  effectiveFrom: string
  effectiveUntil?: string | null
  bands: PriceRevisionBandResponse[]
  lockVersion?: number
}

export interface PriceRevisionSummaryResponse {
  id: string
  productKind: PriceRevisionProductKind
  productKey: string
  scopeKind: BillingScopeKind
  revisionNo: number
  status: PriceRevisionStatus
  effectiveFrom: string
  effectiveUntil?: string | null
}

export interface PriceRevisionPageResponse {
  items: PriceRevisionSummaryResponse[]
  totalElements: number
}

export interface BillingTaxCodeResponse {
  id: string
  code: string
  displayName: string
  stripeTaxCode: string
  rateBasisPoints: number
  validFrom: string
  validUntil?: string | null
  enabled: boolean
}

export interface BillingTaxCodeCreateRequest {
  code: string
  displayName: string
  rateBasisPoints: number
  stripeTaxCode: string
  validFrom: string
  validUntil?: string | null
  enabled: boolean
}

export interface BillingTaxCodeUpdateRequest {
  displayName: string
  stripeTaxCode: string
  validUntil?: string | null
  enabled: boolean
}

/**
 * Billing Center PR6b-1 AC-133: `BillingActiveContract` 投影に載る保留中のプラン変更。
 *
 * <p>生成型（`docs/openapi.json` 再生成後）へ移行済み。BE スキーマは全フィールドが optional
 * （`changeId?` / `status?` / `effectiveAt?` / `paymentActionRequired?` /
 * `pendingUpdateExpiresAt?`）であり、値が無いことは実際に起こりうる状態を表す本物の事実である。
 * 呼び出し側は「無い」を偽の既定値で埋めず、不在として正しく扱うこと
 * （支払期限が無ければ断定しない文言へ、changeId が無ければ 3DS 再開ボタン自体を出さない）。</p>
 */
export type BillingPendingChange = components['schemas']['BillingPendingChange']

// ============================================================
// プラン変更（upgrade）— Billing Center PR6b-1 A群/B群/C群
// ============================================================
//
// 下記は BE の record（`BillingChangePreviewRequest` / `BillingChangePreviewResponse` /
// `BillingPlanChangeRequest` / `BillingContractChangeResponse` / `BillingPaymentActionResponse` /
// `Money`）に対応する生成型（`docs/openapi.json` 再生成済み）を再エクスポートしたもの。
// 以前はここに手書きの暫定型を置いていたが、生成型が真実のソースという方針どおり撤去した。

/** 見積り金額（BE `Money`）。税はすべて BE 由来でこちらで計算しない。 */
export type BillingChangePreviewMoney = components['schemas']['Money']

/** `POST …/change-previews` のリクエスト（AC-17: 価格・band の版は server が tx 内で確定するため送らない）。 */
export type BillingChangePreviewRequestBody = components['schemas']['BillingChangePreviewRequest']

/** `POST …/change-previews` のレスポンス（AC-1）。`amountDueNow` は optional（BE 応答不備時の安全網）。 */
export type BillingChangePreviewResponse = components['schemas']['BillingChangePreviewResponse']

/** `POST …/changes` のリクエスト（AC-25: previewId 必須）。 */
export type BillingPlanChangeExecuteRequest = components['schemas']['BillingPlanChangeRequest']

/**
 * `POST …/changes` のレスポンス（AC-25: clientSecret は返らない）。
 * `status` は生成型では `string` 合併（7値。DOWNGRADE 系の `CREATING_SCHEDULE`/`SCHEDULED` を含む）。
 */
export type BillingContractChangeResponse = components['schemas']['BillingContractChangeResponse']

/**
 * `GET …/changes/{changeId}/payment-action` のレスポンス（AC-48/54）。
 *
 * <p>`clientSecret` は Stripe から都度取得される値であり、DB にも FE の永続領域にも
 * 残してはならない（AC-55〜59）。受け取った値は `stripe.handleNextAction` へ渡す一時変数として
 * だけ扱い、ログ・URL・browser storage・DOM 属性のいずれにも書かないこと。</p>
 */
export type BillingPaymentActionResponse = components['schemas']['BillingPaymentActionResponse']

/** 契約作成に必須の Idempotency-Key ヘッダを生成する（連打・再送の二重発行防止・設計書 02 §0 M-1）。 */
function idempotencyHeaders(key?: string): Record<string, string> {
  return { 'Idempotency-Key': key ?? crypto.randomUUID() }
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
  // 解約（Billing Center PR6a: 期末解約の予約・撤回）
  // ============================================================

  /**
   * 期末解約を予約する（無償契約は即時失効）。
   *
   * <p>正本 05_billing_center.md:334-335（D6）: スコープに関わらず唯一の {@code /me} 配下パスへ
   * 集約する。TEAM/ORG の契約も {@code contractId} で操作し、認可は BE 側がスコープを解決して
   * 判定する。旧 {@code DELETE /me|teams/{id}|organizations/{id}/billing/contracts/{contractId}}
   * （即時削除）を呼んでいた FE 唯一の呼び出し元 {@code BillingManagePanel.vue} は本メソッドへ
   * 完全移行した（Codex 検分 P1 是正）。FE composable からは旧メソッドを削除済み
   * （呼び出し箇所ゼロのため）。BE 側の旧エンドポイント自体の要否は backend 側の判断であり、
   * 本 PR では削除していない。</p>
   *
   * @param contractId 対象契約
   * @param version    契約の CAS 期待値（不一致は 409）
   */
  async function cancelContractReservation(contractId: string, version: number) {
    const body: BillingCancelRequest = { version }
    return api<{ data: BillingContractCancelResponse }>(`/api/v1/me/billing/contracts/${contractId}/cancel`, {
      method: 'POST',
      body,
      headers: idempotencyHeaders(),
    })
  }

  /**
   * 解約予約を撤回する（期末を跨ぐ前に限り可能）。
   *
   * @param contractId 対象契約
   * @param version    契約の CAS 期待値（不一致は 409）
   */
  async function resumeContractCancellation(contractId: string, version: number) {
    const body: BillingCancelRequest = { version }
    return api<{ data: BillingContractCancelResponse }>(`/api/v1/me/billing/contracts/${contractId}/cancel`, {
      method: 'DELETE',
      body,
      headers: idempotencyHeaders(),
    })
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
  // 上位プラン変更（upgrade）と 3DS（Billing Center PR6b-1）
  // ============================================================
  //
  // 解約（PR6a）と同じく、BE のエンドポイントはスコープに関わらず `/me/billing/contracts/{contractId}`
  // 配下の唯一のパスへ集約されている（`BillingPlanChangeController` / `BillingPlanChangePaymentActionController`
  // の `@RequestMapping("/api/v1/me/billing/contracts")` を実読して確認）。TEAM/ORG の契約も
  // contractId で操作し、スコープの解決と認可は BE 側が行う。したがって FE 側にスコープ分岐は無い。

  /**
   * プラン変更の事前見積り（AC-1〜24）。金額は Stripe の見積り API 由来で FE は計算しない。
   *
   * @param contractId 対象契約
   * @param body       変更先プランと契約の CAS 期待値
   */
  async function createPlanChangePreview(contractId: string, body: BillingChangePreviewRequestBody) {
    return api<{ data: BillingChangePreviewResponse }>(`/api/v1/me/billing/contracts/${contractId}/change-previews`, {
      method: 'POST',
      body,
      headers: idempotencyHeaders(),
    })
  }

  /**
   * 見積りを一回だけ消費して upgrade を実行する（AC-25〜31）。
   *
   * <p>202 で `changeId` / `status` / `effectiveAt` が返る。`status` が `REQUIRES_ACTION` の場合は
   * {@link getPlanChangePaymentAction} で 3DS の clientSecret を取得して確認へ進む。</p>
   */
  async function executePlanChange(contractId: string, body: BillingPlanChangeExecuteRequest) {
    return api<{ data: BillingContractChangeResponse }>(`/api/v1/me/billing/contracts/${contractId}/changes`, {
      method: 'POST',
      body,
      headers: idempotencyHeaders(),
    })
  }

  /**
   * 3DS の追加認証情報を都度取得する（AC-48〜54）。
   *
   * <p>Stripe を都度叩く API であるため、呼び出し側は `usePlanChangePolling` の間隔・回数上限の
   * 枠内でのみ使うこと（AC-135）。返る clientSecret は保存せず、その場で
   * `useStripeSetup.confirmPaymentAction` へ渡して捨てる。</p>
   */
  async function getPlanChangePaymentAction(contractId: string, changeId: string) {
    return api<{ data: BillingPaymentActionResponse }>(
      `/api/v1/me/billing/contracts/${contractId}/changes/${changeId}/payment-action`,
    )
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

  // ============================================================
  // 価格改定（price-revisions・SYSTEM_ADMIN専用）
  // ============================================================

  const PRICE_REVISIONS_BASE = `${ADMIN_BASE}/price-revisions`
  const TAX_CODES_BASE = `${ADMIN_BASE}/tax-codes`

  async function createPriceRevision(body: PriceRevisionCreateRequest, idempotencyKey?: string) {
    return api<{ data: PriceRevisionResponse }>(PRICE_REVISIONS_BASE, {
      method: 'POST',
      body,
      headers: idempotencyHeaders(idempotencyKey),
    })
  }

  async function getPriceRevision(id: string) {
    return api<{ data: PriceRevisionResponse }>(`${PRICE_REVISIONS_BASE}/${id}`)
  }

  async function listPriceRevisions(params?: {
    productKind?: PriceRevisionProductKind
    productKey?: string
    scopeKind?: BillingScopeKind
    status?: string
    page?: number
    size?: number
  }) {
    const query = new URLSearchParams()
    if (params?.productKind) query.set('productKind', params.productKind)
    if (params?.productKey) query.set('productKey', params.productKey)
    if (params?.scopeKind) query.set('scopeKind', params.scopeKind)
    if (params?.status) query.set('status', params.status)
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: PriceRevisionPageResponse }>(`${PRICE_REVISIONS_BASE}?${query.toString()}`)
  }

  async function provisionPriceRevision(id: string, lockVersion: number, idempotencyKey?: string) {
    return api<{ data: PriceRevisionResponse }>(`${PRICE_REVISIONS_BASE}/${id}/provision`, {
      method: 'POST',
      body: { lockVersion },
      headers: idempotencyHeaders(idempotencyKey),
    })
  }

  async function retryProvisionPriceRevision(id: string, lockVersion: number, idempotencyKey?: string) {
    return api<{ data: PriceRevisionResponse }>(`${PRICE_REVISIONS_BASE}/${id}/retry-provision`, {
      method: 'POST',
      body: { lockVersion },
      headers: idempotencyHeaders(idempotencyKey),
    })
  }

  async function reconcileProvisionPriceRevision(id: string, lockVersion: number, idempotencyKey?: string) {
    return api<{ data: PriceRevisionResponse }>(`${PRICE_REVISIONS_BASE}/${id}/reconcile-provision`, {
      method: 'POST',
      body: { lockVersion },
      headers: idempotencyHeaders(idempotencyKey),
    })
  }

  /** 価格改定の取り消し（DRAFT / READY / PROVISION_FAILED → CANCELLED。future 枠を解放する）。 */
  async function cancelPriceRevision(id: string, lockVersion: number, idempotencyKey?: string) {
    return api<{ data: PriceRevisionResponse }>(`${PRICE_REVISIONS_BASE}/${id}/cancel`, {
      method: 'POST',
      body: { lockVersion },
      headers: idempotencyHeaders(idempotencyKey),
    })
  }

  async function activatePriceRevision(id: string, lockVersion: number, idempotencyKey?: string) {
    return api<{ data: PriceRevisionResponse }>(`${PRICE_REVISIONS_BASE}/${id}/activate`, {
      method: 'POST',
      body: { lockVersion },
      headers: idempotencyHeaders(idempotencyKey),
    })
  }

  // ============================================================
  // 税コードマスタ（tax-codes・SYSTEM_ADMIN専用）
  // ============================================================

  // 税コード CRUD の応答は ApiResponse 包装なし（SystemAdminTaxCodeController が生 entity/list を返す）。

  async function listTaxCodes() {
    return api<BillingTaxCodeResponse[]>(TAX_CODES_BASE)
  }

  async function createTaxCode(body: BillingTaxCodeCreateRequest) {
    return api<BillingTaxCodeResponse>(TAX_CODES_BASE, { method: 'POST', body })
  }

  async function updateTaxCode(id: string, body: BillingTaxCodeUpdateRequest) {
    return api<BillingTaxCodeResponse>(`${TAX_CODES_BASE}/${id}`, { method: 'PUT', body })
  }

  /** 税コードの論理削除（無効化）。DELETE /tax-codes/{id}。 */
  async function deactivateTaxCode(id: string) {
    return api<unknown>(`${TAX_CODES_BASE}/${id}`, { method: 'DELETE' })
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
    cancelContractReservation,
    resumeContractCancellation,
    changeMyPlan,
    changeTeamPlan,
    changeOrgPlan,
    changePlan,
    createPlanChangePreview,
    executePlanChange,
    getPlanChangePaymentAction,
    listPlansAdmin,
    getPlanAdmin,
    createPlanAdmin,
    updatePlanAdmin,
    deletePlanAdmin,
    replacePlanFeaturesAdmin,
    listFeaturesAdmin,
    getFeatureAdmin,
    createFeatureAdmin,
    updateFeatureAdmin,
    deleteFeatureAdmin,
    grantAdmin,
    searchContractsAdmin,
    createPriceRevision,
    getPriceRevision,
    listPriceRevisions,
    provisionPriceRevision,
    retryProvisionPriceRevision,
    reconcileProvisionPriceRevision,
    activatePriceRevision,
    cancelPriceRevision,
    listTaxCodes,
    createTaxCode,
    updateTaxCode,
    deactivateTaxCode,
  }
}
