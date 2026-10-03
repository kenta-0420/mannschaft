/**
 * F09.19.4b 運用型キャンペーン（バナー広告）CRUD API クライアント（広告主向け）。
 *
 * <p>設計書: docs/features/F09.19_ad_slot_serving.md §6.5 / §8.5。
 * backend は F09.19.1 / F09.19.5 でスコープ化済み:</p>
 * <ul>
 *   <li>{@code /api/v1/organizations/{organizationId}/advertiser/ad-campaigns/*}</li>
 *   <li>{@code /api/v1/teams/{teamId}/advertiser/ad-campaigns/*}</li>
 * </ul>
 *
 * <p>全メソッドの第 1・第 2 引数を (scopeType, scopeId) に統一し、組織/チームの
 * いずれのスコープにも対応する（メッセージ型 {@link useAdMessagingCampaignApi} と同一方式）。
 * 型は生成型（openapi-typescript）を直接使用し、手動型を作らない。</p>
 */
import type { components } from '~/types/generated'
import type { ScopeType } from '~/types/adMessagingCampaign'

/** 運用型キャンペーンのステータス（生成 enum を再掲）。 */
export type OperationalCampaignStatus =
  | 'DRAFT'
  | 'PENDING_REVIEW'
  | 'ACTIVE'
  | 'PAUSED'
  | 'ENDED'

/** 運用型キャンペーン応答（一覧行・詳細）。 */
export type OperationalCampaign = components['schemas']['OperationalCampaignResponse']

/** 運用型キャンペーン作成/編集リクエスト（POST/PUT 共通・全フィールド送信）。 */
export type CreateOperationalCampaignRequest =
  components['schemas']['CreateOperationalCampaignRequest']

/** 公開料金カード（id 付き。F09.19.1b で id を公開）。 */
export type PublicRateCard = components['schemas']['PublicRateCardResponse']

interface ApiEnvelope<T> {
  data: T
}

/**
 * scope に応じた URL セグメントを返す（ORGANIZATION → organizations / TEAM → teams）。
 */
function scopeSegment(scopeType: ScopeType): 'organizations' | 'teams' {
  return scopeType === 'ORGANIZATION' ? 'organizations' : 'teams'
}

/**
 * scope ベースの base path を組み立てる。
 *
 * @example
 *   buildBasePath('ORGANIZATION', '100')
 *     → '/api/v1/organizations/100/advertiser/ad-campaigns'
 */
function buildBasePath(scopeType: ScopeType, scopeId: string): string {
  return `/api/v1/${scopeSegment(scopeType)}/${scopeId}/advertiser/ad-campaigns`
}

export function useOperationalCampaignApi() {
  const api = useApi()

  // ─────────────────────────────────────────────
  // 一覧 / 詳細 / CRUD
  // ─────────────────────────────────────────────

  async function listCampaigns(
    scopeType: ScopeType,
    scopeId: string,
    params?: { status?: OperationalCampaignStatus; page?: number; size?: number },
  ) {
    return api<components['schemas']['PagedResponseOperationalCampaignResponse']>(
      buildBasePath(scopeType, scopeId),
      { params },
    )
  }

  async function getCampaign(scopeType: ScopeType, scopeId: string, campaignId: string) {
    return api<ApiEnvelope<OperationalCampaign>>(
      `${buildBasePath(scopeType, scopeId)}/${campaignId}`,
    )
  }

  async function createCampaign(
    scopeType: ScopeType,
    scopeId: string,
    body: CreateOperationalCampaignRequest,
  ) {
    return api<ApiEnvelope<OperationalCampaign>>(buildBasePath(scopeType, scopeId), {
      method: 'POST',
      body,
    })
  }

  async function updateCampaign(
    scopeType: ScopeType,
    scopeId: string,
    campaignId: string,
    body: CreateOperationalCampaignRequest,
  ) {
    return api<ApiEnvelope<OperationalCampaign>>(
      `${buildBasePath(scopeType, scopeId)}/${campaignId}`,
      { method: 'PUT', body },
    )
  }

  // ─────────────────────────────────────────────
  // 状態遷移（submit / pause / resume / end）
  // ─────────────────────────────────────────────

  async function submitCampaign(scopeType: ScopeType, scopeId: string, campaignId: string) {
    return api<ApiEnvelope<OperationalCampaign>>(
      `${buildBasePath(scopeType, scopeId)}/${campaignId}/submit`,
      { method: 'POST' },
    )
  }

  async function pauseCampaign(scopeType: ScopeType, scopeId: string, campaignId: string) {
    return api<ApiEnvelope<OperationalCampaign>>(
      `${buildBasePath(scopeType, scopeId)}/${campaignId}/pause`,
      { method: 'POST' },
    )
  }

  async function resumeCampaign(scopeType: ScopeType, scopeId: string, campaignId: string) {
    return api<ApiEnvelope<OperationalCampaign>>(
      `${buildBasePath(scopeType, scopeId)}/${campaignId}/resume`,
      { method: 'POST' },
    )
  }

  async function endCampaign(scopeType: ScopeType, scopeId: string, campaignId: string) {
    return api<ApiEnvelope<OperationalCampaign>>(
      `${buildBasePath(scopeType, scopeId)}/${campaignId}/end`,
      { method: 'POST' },
    )
  }

  // ─────────────────────────────────────────────
  // 料金カード（scope 非依存。広告主登録前でも取得可）
  // ─────────────────────────────────────────────

  async function listRateCards(params?: { pricingModel?: 'CPM' | 'CPC'; prefecture?: string }) {
    return api<ApiEnvelope<PublicRateCard[]>>('/api/v1/advertiser/rate-cards', { params })
  }

  return {
    listCampaigns,
    getCampaign,
    createCampaign,
    updateCampaign,
    submitCampaign,
    pauseCampaign,
    resumeCampaign,
    endCampaign,
    listRateCards,
  }
}
