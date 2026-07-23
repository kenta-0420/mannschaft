import type { components } from '~/types/generated'

/**
 * F20.3 ベータ特典 シスアド運用 API の呼び出し（設計書 02 §4）。
 *
 * `useBillingApi` を金型に踏襲。型は生成型（openapi-typescript）を最優先で使う
 * （memory `feedback_fe_api_type_assertion_field_lie`）。全 EP は
 * `@PreAuthorize("hasRole('SYSTEM_ADMIN')")`（非 SYSTEM_ADMIN は 403 → 呼び出し側が
 * `handleApiError` で綺麗に表示する）。
 */

// === 生成型（真実のソース = openapi-typescript）===
export type BetaPerkGrantPageResponse = components['schemas']['BetaPerkGrantPageResponse']
export type BetaPerkGrantDetail = components['schemas']['BetaPerkGrantDetail']
export type BetaPerkCreateGrantRequest = components['schemas']['BetaPerkCreateGrantRequest']
export type BetaPerkRevokeGrantRequest = components['schemas']['BetaPerkRevokeGrantRequest']
export type BetaPerkExtendGrantRequest = components['schemas']['BetaPerkExtendGrantRequest']
export type BetaPerkFlagReviewRequest = components['schemas']['BetaPerkFlagReviewRequest']
export type BetaPerkCandidate = components['schemas']['BetaPerkCandidate']
export type BetaPerkCriteriaResponse = components['schemas']['BetaPerkCriteriaResponse']
export type BetaPerkCriteriaUpsertRequest = components['schemas']['BetaPerkCriteriaUpsertRequest']

// BetaPerkMetricProgress / BetaPerkScopeKind の正準は既存 useBetaPerkApi.ts（単数形・Wave2b）。
// Nuxt 自動 import の二重 re-export 警告を避けるため、ここでは再宣言しない。

/** 付与種別（設計書 02 §4.1）。 */
export type BetaPerkGrantKind = 'INDIVIDUAL' | 'TEAM_ORG'

export function useBetaPerksApi() {
  const api = useApi()

  const BASE = '/api/v1/system-admin/beta-perks'

  // ============================================================
  // 付与一覧・手動付与・取消・延長・審査（設計書 02 §4.1〜4.4）
  // ============================================================

  /** 付与一覧（ページング）。フィルタ: 付与種別 / フェーズ / 審査フラグ。 */
  async function listGrants(params?: {
    grantKind?: BetaPerkGrantKind
    betaPhase?: number
    reviewFlag?: boolean
    page?: number
    size?: number
  }) {
    const query = new URLSearchParams()
    if (params?.grantKind) query.set('grantKind', params.grantKind)
    if (params?.betaPhase != null) query.set('betaPhase', String(params.betaPhase))
    if (params?.reviewFlag != null) query.set('reviewFlag', String(params.reviewFlag))
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: BetaPerkGrantPageResponse }>(`${BASE}/grants?${query.toString()}`)
  }

  /** 手動付与（TEAM_ORG の正規経路・INDIVIDUAL も可）。 */
  async function createGrant(body: BetaPerkCreateGrantRequest) {
    return api<{ data: BetaPerkGrantDetail }>(`${BASE}/grants`, { method: 'POST', body })
  }

  /** 取消（理由必須）。 */
  async function revokeGrant(grantId: string, body: BetaPerkRevokeGrantRequest) {
    return api<{ data: BetaPerkGrantDetail }>(`${BASE}/grants/${grantId}/revoke`, { method: 'POST', body })
  }

  /** 延長（TEAM_ORG のみ・1〜24 か月）。 */
  async function extendGrant(grantId: string, body: BetaPerkExtendGrantRequest) {
    return api<{ data: BetaPerkGrantDetail }>(`${BASE}/grants/${grantId}/extend`, { method: 'POST', body })
  }

  /** 審査解決（review_flag=true の grant のみ・body なし）。 */
  async function resolveReview(grantId: string) {
    return api<{ data: BetaPerkGrantDetail }>(`${BASE}/grants/${grantId}/resolve-review`, { method: 'POST' })
  }

  /** 手動フラグ（review_reason='MANUAL'）。 */
  async function flagReview(grantId: string, body: BetaPerkFlagReviewRequest) {
    return api<{ data: BetaPerkGrantDetail }>(`${BASE}/grants/${grantId}/flag-review`, { method: 'POST', body })
  }

  // ============================================================
  // 付与候補（dry-run）・条件マスタ CRUD（設計書 02 §4.5〜4.6）
  // ============================================================

  /** 付与候補（dry-run・未付与かつ充足のスコープ。付与はしない）。 */
  async function listCandidates(params?: {
    grantKind?: BetaPerkGrantKind
    betaPhase?: number
    page?: number
    size?: number
  }) {
    const query = new URLSearchParams()
    if (params?.grantKind) query.set('grantKind', params.grantKind)
    if (params?.betaPhase != null) query.set('betaPhase', String(params.betaPhase))
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: BetaPerkCandidate[] }>(`${BASE}/candidates?${query.toString()}`)
  }

  /** 条件マスタ取得（複合自然キー PATH・未定義/enabled=false は 404）。 */
  async function getCriteria(betaPhase: number, grantKind: BetaPerkGrantKind) {
    return api<{ data: BetaPerkCriteriaResponse }>(`${BASE}/criteria/${betaPhase}/${grantKind}`)
  }

  /** 条件マスタ更新（最低 1 指標非 NULL・window 1〜365）。 */
  async function upsertCriteria(betaPhase: number, grantKind: BetaPerkGrantKind, body: BetaPerkCriteriaUpsertRequest) {
    return api<{ data: BetaPerkCriteriaResponse }>(`${BASE}/criteria/${betaPhase}/${grantKind}`, { method: 'PUT', body })
  }

  return {
    listGrants,
    createGrant,
    revokeGrant,
    extendGrant,
    resolveReview,
    flagReview,
    listCandidates,
    getCriteria,
    upsertCriteria,
  }
}
