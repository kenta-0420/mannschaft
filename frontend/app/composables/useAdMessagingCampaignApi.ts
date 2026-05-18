// F09.17 広告主向けメッセージ型キャンペーン API クライアント
// 設計書: docs/features/F09.17_advertiser_targeted_campaign.md §4
// 型は Phase 11-c-1 で導入された `~/types/adMessagingCampaign` を利用する。

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

export function useAdMessagingCampaignApi() {
  const api = useApi()

  const base = '/api/v1/advertiser/campaigns/messaging'

  // ─────────────────────────────────────────────
  // 一覧 / 詳細 / CRUD
  // ─────────────────────────────────────────────

  async function listCampaigns(
    organizationId: number,
    params?: { status?: AdMessagingCampaignStatus; page?: number; size?: number },
  ) {
    return api<PagedEnvelope<AdMessagingCampaignListItem>>(base, {
      params: { organizationId, ...params },
    })
  }

  async function getCampaign(organizationId: number, campaignId: string) {
    return api<ApiEnvelope<AdMessagingCampaign>>(`${base}/${campaignId}`, {
      params: { organizationId },
    })
  }

  async function createCampaign(organizationId: number, body: CreateAdMessagingCampaignRequest) {
    return api<ApiEnvelope<AdMessagingCampaign>>(base, {
      method: 'POST',
      params: { organizationId },
      body,
    })
  }

  async function updateCampaign(
    organizationId: number,
    campaignId: string,
    body: UpdateAdMessagingCampaignRequest,
  ) {
    return api<ApiEnvelope<AdMessagingCampaign>>(`${base}/${campaignId}`, {
      method: 'PUT',
      params: { organizationId },
      body,
    })
  }

  async function deleteCampaign(organizationId: number, campaignId: string) {
    return api(`${base}/${campaignId}`, {
      method: 'DELETE',
      params: { organizationId },
    })
  }

  // ─────────────────────────────────────────────
  // チャネル CRUD
  // ─────────────────────────────────────────────

  async function createChannel(
    organizationId: number,
    campaignId: string,
    body: AdMessagingCampaignChannelRequest,
  ) {
    return api<ApiEnvelope<AdMessagingCampaignChannel>>(`${base}/${campaignId}/channels`, {
      method: 'POST',
      params: { organizationId },
      body,
    })
  }

  async function updateChannel(
    organizationId: number,
    campaignId: string,
    channelId: string,
    body: AdMessagingCampaignChannelRequest,
  ) {
    return api<ApiEnvelope<AdMessagingCampaignChannel>>(
      `${base}/${campaignId}/channels/${channelId}`,
      {
        method: 'PUT',
        params: { organizationId },
        body,
      },
    )
  }

  async function deleteChannel(organizationId: number, campaignId: string, channelId: string) {
    return api(`${base}/${campaignId}/channels/${channelId}`, {
      method: 'DELETE',
      params: { organizationId },
    })
  }

  // ─────────────────────────────────────────────
  // オーディエンス（全件 replace）
  // ─────────────────────────────────────────────

  async function setAudience(
    organizationId: number,
    campaignId: string,
    body: AdMessagingCampaignAudienceConfigRequest,
  ) {
    return api<ApiEnvelope<AdMessagingCampaignAudienceSegment[]>>(
      `${base}/${campaignId}/audience`,
      {
        method: 'POST',
        params: { organizationId },
        body,
      },
    )
  }

  // ─────────────────────────────────────────────
  // Preview / Report
  // ─────────────────────────────────────────────

  async function previewCampaign(organizationId: number, campaignId: string) {
    return api<ApiEnvelope<AdCampaignPreviewResponse>>(`${base}/${campaignId}/preview`, {
      method: 'POST',
      params: { organizationId },
    })
  }

  async function getReport(
    organizationId: number,
    campaignId: string,
    params?: { from?: string; to?: string },
  ) {
    return api<ApiEnvelope<AdCampaignReport>>(`${base}/${campaignId}/report`, {
      params: { organizationId, ...params },
    })
  }

  // ─────────────────────────────────────────────
  // 状態遷移
  // ─────────────────────────────────────────────

  async function submitCampaign(organizationId: number, campaignId: string) {
    return api<ApiEnvelope<AdMessagingCampaign>>(`${base}/${campaignId}/submit`, {
      method: 'POST',
      params: { organizationId },
    })
  }

  async function cancelCampaign(organizationId: number, campaignId: string) {
    return api<ApiEnvelope<AdMessagingCampaign>>(`${base}/${campaignId}/cancel`, {
      method: 'POST',
      params: { organizationId },
    })
  }

  async function launchCampaign(organizationId: number, campaignId: string) {
    return api<ApiEnvelope<AdMessagingCampaign>>(`${base}/${campaignId}/launch`, {
      method: 'POST',
      params: { organizationId },
    })
  }

  async function pauseCampaign(organizationId: number, campaignId: string) {
    return api<ApiEnvelope<AdMessagingCampaign>>(`${base}/${campaignId}/pause`, {
      method: 'POST',
      params: { organizationId },
    })
  }

  async function resumeCampaign(organizationId: number, campaignId: string) {
    return api<ApiEnvelope<AdMessagingCampaign>>(`${base}/${campaignId}/resume`, {
      method: 'POST',
      params: { organizationId },
    })
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
