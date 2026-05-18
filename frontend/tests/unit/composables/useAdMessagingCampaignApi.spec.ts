import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F09.17 useAdMessagingCampaignApi ユニットテスト
 *
 * 観点:
 *  - CRUD / Channel / Audience / Preview / Report / 状態遷移 の各メソッドが
 *    期待する URL / method / params / body で `useApi` を呼び出すこと
 *  - 戻り値の型が型定義と一致する（コンパイル時に型エラーが出ないことで担保）
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useAdMessagingCampaignApi } = await import('~/composables/useAdMessagingCampaignApi')

describe('useAdMessagingCampaignApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockFetch.mockResolvedValue({ data: {}, meta: { totalElements: 0, page: 0, size: 20, totalPages: 0 } })
  })

  const orgId = 100
  const campaignId = '0190abcdefab0000000000000000abcd'
  const channelId = '0190abcdefab0000000000000000bbbb'

  it('AMC-API-001: listCampaigns GET base + organizationId', async () => {
    const api = useAdMessagingCampaignApi()
    await api.listCampaigns(orgId, { status: 'DRAFT', page: 1, size: 50 })
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/advertiser/campaigns/messaging',
      expect.objectContaining({
        params: { organizationId: orgId, status: 'DRAFT', page: 1, size: 50 },
      }),
    )
  })

  it('AMC-API-002: getCampaign GET base/{id}', async () => {
    const api = useAdMessagingCampaignApi()
    await api.getCampaign(orgId, campaignId)
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/advertiser/campaigns/messaging/${campaignId}`,
      expect.objectContaining({ params: { organizationId: orgId } }),
    )
  })

  it('AMC-API-003: createCampaign POST base + body', async () => {
    const api = useAdMessagingCampaignApi()
    await api.createCampaign(orgId, {
      name: 'test',
      totalBudgetYen: 10000,
      startsAt: '2026-06-01T00:00:00',
      endsAt: '2026-06-30T00:00:00',
      scheduledTimezone: 'Asia/Tokyo',
      frequencyCapOverride: null,
    })
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/advertiser/campaigns/messaging',
      expect.objectContaining({
        method: 'POST',
        params: { organizationId: orgId },
        body: expect.objectContaining({ name: 'test', totalBudgetYen: 10000 }),
      }),
    )
  })

  it('AMC-API-004: updateCampaign PUT base/{id}', async () => {
    const api = useAdMessagingCampaignApi()
    await api.updateCampaign(orgId, campaignId, {
      name: 'updated',
      totalBudgetYen: 20000,
      startsAt: '2026-06-01T00:00:00',
      endsAt: '2026-06-30T00:00:00',
      scheduledTimezone: 'Asia/Tokyo',
      frequencyCapOverride: 5,
    })
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/advertiser/campaigns/messaging/${campaignId}`,
      expect.objectContaining({ method: 'PUT' }),
    )
  })

  it('AMC-API-005: deleteCampaign DELETE base/{id}', async () => {
    const api = useAdMessagingCampaignApi()
    await api.deleteCampaign(orgId, campaignId)
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/advertiser/campaigns/messaging/${campaignId}`,
      expect.objectContaining({ method: 'DELETE' }),
    )
  })

  it('AMC-API-006: createChannel POST base/{id}/channels', async () => {
    const api = useAdMessagingCampaignApi()
    await api.createChannel(orgId, campaignId, {
      channelType: 'EMAIL',
      locale: 'ja',
      bodyMarkdown: 'hello',
    })
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/advertiser/campaigns/messaging/${campaignId}/channels`,
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('AMC-API-007: updateChannel PUT base/{id}/channels/{channelId}', async () => {
    const api = useAdMessagingCampaignApi()
    await api.updateChannel(orgId, campaignId, channelId, {
      channelType: 'EMAIL',
      locale: 'ja',
      bodyMarkdown: 'hi',
    })
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/advertiser/campaigns/messaging/${campaignId}/channels/${channelId}`,
      expect.objectContaining({ method: 'PUT' }),
    )
  })

  it('AMC-API-008: deleteChannel DELETE', async () => {
    const api = useAdMessagingCampaignApi()
    await api.deleteChannel(orgId, campaignId, channelId)
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/advertiser/campaigns/messaging/${campaignId}/channels/${channelId}`,
      expect.objectContaining({ method: 'DELETE' }),
    )
  })

  it('AMC-API-009: setAudience POST audience', async () => {
    const api = useAdMessagingCampaignApi()
    await api.setAudience(orgId, campaignId, {
      segments: [{ segmentType: 'AGE_RANGE', segmentValue: { min: 20, max: 39 }, inclusionMode: 'INCLUDE' }],
    })
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/advertiser/campaigns/messaging/${campaignId}/audience`,
      expect.objectContaining({
        method: 'POST',
        body: expect.objectContaining({ segments: expect.any(Array) }),
      }),
    )
  })

  it('AMC-API-010: previewCampaign POST preview', async () => {
    const api = useAdMessagingCampaignApi()
    await api.previewCampaign(orgId, campaignId)
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/advertiser/campaigns/messaging/${campaignId}/preview`,
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('AMC-API-011: getReport GET report', async () => {
    const api = useAdMessagingCampaignApi()
    await api.getReport(orgId, campaignId, { from: '2026-05-01', to: '2026-05-31' })
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/advertiser/campaigns/messaging/${campaignId}/report`,
      expect.objectContaining({
        params: expect.objectContaining({ organizationId: orgId, from: '2026-05-01' }),
      }),
    )
  })

  it.each([
    ['submit', 'submitCampaign'],
    ['cancel', 'cancelCampaign'],
    ['launch', 'launchCampaign'],
    ['pause', 'pauseCampaign'],
    ['resume', 'resumeCampaign'],
  ])('AMC-API-T-%s: %s POST', async (path, method) => {
    const api = useAdMessagingCampaignApi() as unknown as Record<string, (o: number, c: string) => Promise<unknown>>
    const fn = api[method]
    if (!fn) throw new Error(`Method ${method} not found`)
    await fn(orgId, campaignId)
    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/advertiser/campaigns/messaging/${campaignId}/${path}`,
      expect.objectContaining({ method: 'POST' }),
    )
  })
})
