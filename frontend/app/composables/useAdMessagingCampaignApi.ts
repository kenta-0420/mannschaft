// F09.17 広告主向けメッセージ型キャンペーン API クライアント
// 設計書: docs/features/F09.17_advertiser_targeted_campaign.md §4
//
// F09.17 Phase 11-d-3 で scope ベース URL に切り替え:
//  - 旧 URL: /api/v1/advertiser/campaigns/messaging/* (organizationId クエリ式 / Deprecated)
//  - 新 URL: /api/v1/{organizations|teams}/{scopeId}/advertiser/campaigns/messaging/*
//
// 全メソッドの第 1・第 2 引数を (scopeType, scopeId) に統一し、
// 組織/チームのいずれのスコープにも対応できるようにする。

import type {
  AdCampaignPreviewResponse,
  AdCampaignReport,
  AdMessagingCampaign,
  AdMessagingCampaignAudienceConfigRequest,
  AdMessagingCampaignAudienceSegment,
  AdMessagingCampaignChannel,
  AdMessagingCampaignChannelRequest,
  AdMessagingCampaignListItem,
  AdMessagingCampaignStatus,
  CreateAdMessagingCampaignRequest,
  ScopeType,
  UpdateAdMessagingCampaignRequest,
} from '~/types/adMessagingCampaign'

interface ApiEnvelope<T> {
  data: T
}

interface PageMeta {
  totalElements: number
  page: number
  size: number
  totalPages: number
}

interface PagedEnvelope<T> {
  data: T[]
  meta: PageMeta
}

/**
 * scope に応じた URL セグメントを返す。
 *
 * <p>{@code ORGANIZATION} → {@code organizations}、{@code TEAM} → {@code teams}。
 * backend Controller の {@code @RequestMapping} に追従する。</p>
 */
function scopeSegment(scopeType: ScopeType): 'organizations' | 'teams' {
  return scopeType === 'ORGANIZATION' ? 'organizations' : 'teams'
}

/**
 * scope ベースの base path を組み立てる。
 *
 * @example
 *   buildBasePath('ORGANIZATION', 100)
 *     → '/api/v1/organizations/100/advertiser/campaigns/messaging'
 *   buildBasePath('TEAM', 42)
 *     → '/api/v1/teams/42/advertiser/campaigns/messaging'
 */
function buildBasePath(scopeType: ScopeType, scopeId: string): string {
  return `/api/v1/${scopeSegment(scopeType)}/${scopeId}/advertiser/campaigns/messaging`
}

export function useAdMessagingCampaignApi() {
  const api = useApi()

  // ─────────────────────────────────────────────
  // 一覧 / 詳細 / CRUD
  // ─────────────────────────────────────────────

  async function listCampaigns(
    scopeType: ScopeType,
    scopeId: string,
    params?: { status?: AdMessagingCampaignStatus; page?: number; size?: number },
  ) {
    return api<PagedEnvelope<AdMessagingCampaignListItem>>(buildBasePath(scopeType, scopeId), {
      params,
    })
  }

  async function getCampaign(scopeType: ScopeType, scopeId: string, campaignId: string) {
    return api<ApiEnvelope<AdMessagingCampaign>>(
      `${buildBasePath(scopeType, scopeId)}/${campaignId}`,
    )
  }

  async function createCampaign(
    scopeType: ScopeType,
    scopeId: string,
    body: CreateAdMessagingCampaignRequest,
  ) {
    return api<ApiEnvelope<AdMessagingCampaign>>(buildBasePath(scopeType, scopeId), {
      method: 'POST',
      body,
    })
  }

  async function updateCampaign(
    scopeType: ScopeType,
    scopeId: string,
    campaignId: string,
    body: UpdateAdMessagingCampaignRequest,
  ) {
    return api<ApiEnvelope<AdMessagingCampaign>>(
      `${buildBasePath(scopeType, scopeId)}/${campaignId}`,
      {
        method: 'PUT',
        body,
      },
    )
  }

  async function deleteCampaign(scopeType: ScopeType, scopeId: string, campaignId: string) {
    return api(`${buildBasePath(scopeType, scopeId)}/${campaignId}`, {
      method: 'DELETE',
    })
  }

  // ─────────────────────────────────────────────
  // チャネル CRUD
  // ─────────────────────────────────────────────

  async function createChannel(
    scopeType: ScopeType,
    scopeId: string,
    campaignId: string,
    body: AdMessagingCampaignChannelRequest,
  ) {
    return api<ApiEnvelope<AdMessagingCampaignChannel>>(
      `${buildBasePath(scopeType, scopeId)}/${campaignId}/channels`,
      {
        method: 'POST',
        body,
      },
    )
  }

  async function updateChannel(
    scopeType: ScopeType,
    scopeId: string,
    campaignId: string,
    channelId: string,
    body: AdMessagingCampaignChannelRequest,
  ) {
    return api<ApiEnvelope<AdMessagingCampaignChannel>>(
      `${buildBasePath(scopeType, scopeId)}/${campaignId}/channels/${channelId}`,
      {
        method: 'PUT',
        body,
      },
    )
  }

  async function deleteChannel(
    scopeType: ScopeType,
    scopeId: string,
    campaignId: string,
    channelId: string,
  ) {
    return api(`${buildBasePath(scopeType, scopeId)}/${campaignId}/channels/${channelId}`, {
      method: 'DELETE',
    })
  }

  // ─────────────────────────────────────────────
  // オーディエンス（全件 replace）
  // ─────────────────────────────────────────────

  async function setAudience(
    scopeType: ScopeType,
    scopeId: string,
    campaignId: string,
    body: AdMessagingCampaignAudienceConfigRequest,
  ) {
    return api<ApiEnvelope<AdMessagingCampaignAudienceSegment[]>>(
      `${buildBasePath(scopeType, scopeId)}/${campaignId}/audience`,
      {
        method: 'POST',
        body,
      },
    )
  }

  // ─────────────────────────────────────────────
  // Preview / Report
  //
  // 注: backend は Phase 11-b で実装予定（11-d 時点で未実装）。
  // 11-d-3 では新 URL のスケルトンを用意するに留め、実呼び出しは backend 実装後に通る想定。
  // ─────────────────────────────────────────────

  async function previewCampaign(scopeType: ScopeType, scopeId: string, campaignId: string) {
    return api<ApiEnvelope<AdCampaignPreviewResponse>>(
      `${buildBasePath(scopeType, scopeId)}/${campaignId}/preview`,
      {
        method: 'POST',
      },
    )
  }

  async function getReport(
    scopeType: ScopeType,
    scopeId: string,
    campaignId: string,
    params?: { from?: string; to?: string },
  ) {
    return api<ApiEnvelope<AdCampaignReport>>(
      `${buildBasePath(scopeType, scopeId)}/${campaignId}/report`,
      {
        params,
      },
    )
  }

  // ─────────────────────────────────────────────
  // 状態遷移（submit / cancel / launch / pause / resume）
  // ─────────────────────────────────────────────

  async function submitCampaign(scopeType: ScopeType, scopeId: string, campaignId: string) {
    return api<ApiEnvelope<AdMessagingCampaign>>(
      `${buildBasePath(scopeType, scopeId)}/${campaignId}/submit`,
      {
        method: 'POST',
      },
    )
  }

  async function cancelCampaign(scopeType: ScopeType, scopeId: string, campaignId: string) {
    return api<ApiEnvelope<AdMessagingCampaign>>(
      `${buildBasePath(scopeType, scopeId)}/${campaignId}/cancel`,
      {
        method: 'POST',
      },
    )
  }

  async function launchCampaign(scopeType: ScopeType, scopeId: string, campaignId: string) {
    return api<ApiEnvelope<AdMessagingCampaign>>(
      `${buildBasePath(scopeType, scopeId)}/${campaignId}/launch`,
      {
        method: 'POST',
      },
    )
  }

  async function pauseCampaign(scopeType: ScopeType, scopeId: string, campaignId: string) {
    return api<ApiEnvelope<AdMessagingCampaign>>(
      `${buildBasePath(scopeType, scopeId)}/${campaignId}/pause`,
      {
        method: 'POST',
      },
    )
  }

  async function resumeCampaign(scopeType: ScopeType, scopeId: string, campaignId: string) {
    return api<ApiEnvelope<AdMessagingCampaign>>(
      `${buildBasePath(scopeType, scopeId)}/${campaignId}/resume`,
      {
        method: 'POST',
      },
    )
  }

  return {
    // CRUD
    listCampaigns,
    getCampaign,
    createCampaign,
    updateCampaign,
    deleteCampaign,
    // Channel
    createChannel,
    updateChannel,
    deleteChannel,
    // Audience
    setAudience,
    // Preview / Report
    previewCampaign,
    getReport,
    // Transition
    submitCampaign,
    cancelCampaign,
    launchCampaign,
    pauseCampaign,
    resumeCampaign,
  }
}
