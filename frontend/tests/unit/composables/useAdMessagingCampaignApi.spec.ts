import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F09.17 useAdMessagingCampaignApi ユニットテスト
 *
 * 観点:
 *  - CRUD / Channel / Audience / Preview / Report / 状態遷移 の各メソッドが
 *    期待する URL / method / params / body で `useApi` を呼び出すこと
 *  - 戻り値の型が型定義と一致する（コンパイル時に型エラーが出ないことで担保）
 *
 * Phase 11-d-3 で scope ベース URL に切替:
 *  - 旧: /api/v1/advertiser/campaigns/messaging/* (organizationId クエリ)
 *  - 新: /api/v1/{organizations|teams}/{scopeId}/advertiser/campaigns/messaging/*
 *
 * テストでは ORGANIZATION スコープと TEAM スコープの両方を検証する。
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useAdMessagingCampaignApi } = await import('~/composables/useAdMessagingCampaignApi')

describe('useAdMessagingCampaignApi (scope-based URL)', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockFetch.mockResolvedValue({
      data: {},
      meta: { totalElements: 0, page: 0, size: 20, totalPages: 0 },
    })
  })

  const orgId = '100'
  const teamId = '42'
  const campaignId = '0190abcdefab0000000000000000abcd'
  const channelId = '0190abcdefab0000000000000000bbbb'

  // ─── 一覧 / 詳細 / CRUD ───────────────────────────

  it('AMC-API-001: listCampaigns (ORGANIZATION) GET /organizations/{id}/...', async () => {
    const api = useAdMessagingCampaignApi()
    await api.listCampaigns('ORGANIZATION', orgId, { status: 'DRAFT', page: 1, size: 50 })
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${orgId}/advertiser/campaigns/messaging`,
      expect.objectContaining({
        params: { status: 'DRAFT', page: 1, size: 50 },
      }),
    )
  })

  it('AMC-API-001b: listCampaigns (TEAM) GET /teams/{id}/...', async () => {
    const api = useAdMessagingCampaignApi()
    await api.listCampaigns('TEAM', teamId, { status: 'DRAFT' })
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/teams/${teamId}/advertiser/campaigns/messaging`,
      expect.objectContaining({
        params: { status: 'DRAFT' },
      }),
    )
  })

  it('AMC-API-002: getCampaign GET base/{id}', async () => {
    const api = useAdMessagingCampaignApi()
    await api.getCampaign('ORGANIZATION', orgId, campaignId)
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${orgId}/advertiser/campaigns/messaging/${campaignId}`,
    )
  })

  it('AMC-API-003: createCampaign POST base + body', async () => {
    const api = useAdMessagingCampaignApi()
    await api.createCampaign('ORGANIZATION', orgId, {
      name: 'test',
      totalBudgetYen: 10000,
      startsAt: '2026-06-01T00:00:00',
      endsAt: '2026-06-30T00:00:00',
      scheduledTimezone: 'Asia/Tokyo',
      frequencyCapOverride: null,
    })
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${orgId}/advertiser/campaigns/messaging`,
      expect.objectContaining({
        method: 'POST',
        body: expect.objectContaining({ name: 'test', totalBudgetYen: 10000 }),
      }),
    )
  })

  it('AMC-API-003b: createCampaign (TEAM) POST team base', async () => {
    const api = useAdMessagingCampaignApi()
    await api.createCampaign('TEAM', teamId, {
      name: 't',
      totalBudgetYen: 5000,
      startsAt: '2026-06-01T00:00:00',
      endsAt: '2026-06-30T00:00:00',
      scheduledTimezone: 'Asia/Tokyo',
      frequencyCapOverride: null,
    })
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/teams/${teamId}/advertiser/campaigns/messaging`,
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('AMC-API-004: updateCampaign PUT base/{id}', async () => {
    const api = useAdMessagingCampaignApi()
    await api.updateCampaign('ORGANIZATION', orgId, campaignId, {
      name: 'updated',
      totalBudgetYen: 20000,
      startsAt: '2026-06-01T00:00:00',
      endsAt: '2026-06-30T00:00:00',
      scheduledTimezone: 'Asia/Tokyo',
      frequencyCapOverride: 5,
    })
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${orgId}/advertiser/campaigns/messaging/${campaignId}`,
      expect.objectContaining({ method: 'PUT' }),
    )
  })

  it('AMC-API-005: deleteCampaign DELETE base/{id}', async () => {
    const api = useAdMessagingCampaignApi()
    await api.deleteCampaign('ORGANIZATION', orgId, campaignId)
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${orgId}/advertiser/campaigns/messaging/${campaignId}`,
      expect.objectContaining({ method: 'DELETE' }),
    )
  })

  // ─── チャネル CRUD ────────────────────────────────

  it('AMC-API-006: createChannel POST base/{id}/channels', async () => {
    const api = useAdMessagingCampaignApi()
    await api.createChannel('ORGANIZATION', orgId, campaignId, {
      channelType: 'EMAIL',
      locale: 'ja',
      bodyMarkdown: 'hello',
    })
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${orgId}/advertiser/campaigns/messaging/${campaignId}/channels`,
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('AMC-API-007: updateChannel PUT base/{id}/channels/{channelId}', async () => {
    const api = useAdMessagingCampaignApi()
    await api.updateChannel('ORGANIZATION', orgId, campaignId, channelId, {
      channelType: 'EMAIL',
      locale: 'ja',
      bodyMarkdown: 'hi',
    })
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${orgId}/advertiser/campaigns/messaging/${campaignId}/channels/${channelId}`,
      expect.objectContaining({ method: 'PUT' }),
    )
  })

  it('AMC-API-008: deleteChannel DELETE', async () => {
    const api = useAdMessagingCampaignApi()
    await api.deleteChannel('ORGANIZATION', orgId, campaignId, channelId)
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${orgId}/advertiser/campaigns/messaging/${campaignId}/channels/${channelId}`,
      expect.objectContaining({ method: 'DELETE' }),
    )
  })

  // ─── オーディエンス ───────────────────────────────

  it('AMC-API-009: setAudience POST audience', async () => {
    const api = useAdMessagingCampaignApi()
    await api.setAudience('ORGANIZATION', orgId, campaignId, {
      segments: [
        { segmentType: 'AGE_RANGE', segmentValue: { min: 20, max: 39 }, inclusionMode: 'INCLUDE' },
      ],
    })
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${orgId}/advertiser/campaigns/messaging/${campaignId}/audience`,
      expect.objectContaining({
        method: 'POST',
        body: expect.objectContaining({ segments: expect.any(Array) }),
      }),
    )
  })

  // ─── Preview / Report ─────────────────────────────

  it('AMC-API-010: previewCampaign POST preview', async () => {
    const api = useAdMessagingCampaignApi()
    await api.previewCampaign('ORGANIZATION', orgId, campaignId)
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${orgId}/advertiser/campaigns/messaging/${campaignId}/preview`,
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('AMC-API-011: getReport GET report (organization)', async () => {
    const api = useAdMessagingCampaignApi()
    await api.getReport('ORGANIZATION', orgId, campaignId, { from: '2026-05-01', to: '2026-05-31' })
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${orgId}/advertiser/campaigns/messaging/${campaignId}/report`,
      expect.objectContaining({
        params: { from: '2026-05-01', to: '2026-05-31' },
      }),
    )
  })

  // ─── 状態遷移 ─────────────────────────────────────

  it.each([
    ['submit', 'submitCampaign'],
    ['cancel', 'cancelCampaign'],
    ['launch', 'launchCampaign'],
    ['pause', 'pauseCampaign'],
    ['resume', 'resumeCampaign'],
  ])('AMC-API-T-%s: %s POST (organization scope)', async (path, method) => {
    const api = useAdMessagingCampaignApi() as unknown as Record<
      string,
      (s: 'ORGANIZATION' | 'TEAM', id: string, c: string) => Promise<unknown>
    >
    const fn = api[method]
    if (!fn) throw new Error(`Method ${method} not found`)
    await fn('ORGANIZATION', orgId, campaignId)
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${orgId}/advertiser/campaigns/messaging/${campaignId}/${path}`,
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it.each([
    ['submit', 'submitCampaign'],
    ['cancel', 'cancelCampaign'],
    ['launch', 'launchCampaign'],
    ['pause', 'pauseCampaign'],
    ['resume', 'resumeCampaign'],
  ])('AMC-API-T-team-%s: %s POST (team scope)', async (path, method) => {
    const api = useAdMessagingCampaignApi() as unknown as Record<
      string,
      (s: 'ORGANIZATION' | 'TEAM', id: string, c: string) => Promise<unknown>
    >
    const fn = api[method]
    if (!fn) throw new Error(`Method ${method} not found`)
    await fn('TEAM', teamId, campaignId)
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/teams/${teamId}/advertiser/campaigns/messaging/${campaignId}/${path}`,
      expect.objectContaining({ method: 'POST' }),
    )
  })
})
