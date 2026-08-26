import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F09.17 useAdDeliveriesApi ユニットテスト
 *
 * 検証観点:
 *   ADV-API-001: listDeliveries → GET /api/v1/me/ad-deliveries に params を渡す
 *   ADV-API-002: deleteAllDeliveries → DELETE /api/v1/me/ad-deliveries
 *   ADV-API-003: createReport → POST /api/v1/me/ad-reports に body を渡す
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useAdDeliveriesApi } = await import('~/composables/useAdDeliveriesApi')

describe('useAdDeliveriesApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('ADV-API-001: listDeliveries は GET /api/v1/me/ad-deliveries に params を渡す', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [],
      meta: { nextCursor: null, limit: 20, hasNext: false },
    })
    const api = useAdDeliveriesApi()
    await api.listDeliveries({ channelType: 'EMAIL', limit: 20 })

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/ad-deliveries', {
      params: { channelType: 'EMAIL', limit: 20 },
    })
  })

  it('ADV-API-002: deleteAllDeliveries は DELETE /api/v1/me/ad-deliveries', async () => {
    mockFetch.mockResolvedValueOnce(undefined)
    const api = useAdDeliveriesApi()
    await api.deleteAllDeliveries()

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/ad-deliveries', {
      method: 'DELETE',
    })
  })

  it('ADV-API-003: createReport は POST /api/v1/me/ad-reports に body を渡す', async () => {
    // F09.19.9: レスポンスは { id, status, createdAt }（BE 契約）
    mockFetch.mockResolvedValueOnce({
      data: {
        id: 'rep-1',
        status: 'NEW',
        createdAt: '2026-05-17T00:00:00Z',
      },
    })
    const api = useAdDeliveriesApi()
    // F09.19.9: リクエストは XOR（campaignId / operationalCampaignId）+ channelType + reasonCode + comment
    const body = {
      campaignId: 'cmp-1',
      channelType: 'BANNER' as const,
      reasonCode: 'MISLEADING' as const,
      comment: '誇大',
    }
    await api.createReport(body)

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/ad-reports', {
      method: 'POST',
      body,
    })
  })
})
