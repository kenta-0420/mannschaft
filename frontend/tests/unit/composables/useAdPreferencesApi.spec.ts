import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F09.17 useAdPreferencesApi ユニットテスト
 *
 * 検証観点:
 *   ADP-API-001: getPreferences → GET /api/v1/me/ad-preferences を呼び {data} を返す
 *   ADP-API-002: updatePreferences → PUT で body を渡す
 *   ADP-API-003: rotateUnsubscribeToken → PUT で rotateUnsubscribeToken=true を送る
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useAdPreferencesApi } = await import('~/composables/useAdPreferencesApi')

describe('useAdPreferencesApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('ADP-API-001: getPreferences は GET /api/v1/me/ad-preferences を呼ぶ', async () => {
    mockFetch.mockResolvedValueOnce({
      data: {
        acceptAnnouncementAds: true,
        acceptEmailAds: true,
        acceptPushAds: true,
        acceptBannerAds: true,
        blockedAdvertiserAccountIds: [],
        consentedAt: null,
        unsubscribeTokenVersion: 1,
      },
    })
    const api = useAdPreferencesApi()
    const res = await api.getPreferences()

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/ad-preferences')
    expect(res.data.acceptAnnouncementAds).toBe(true)
  })

  it('ADP-API-002: updatePreferences は PUT で body を渡す', async () => {
    mockFetch.mockResolvedValueOnce({
      data: {
        acceptAnnouncementAds: false,
        acceptEmailAds: true,
        acceptPushAds: true,
        acceptBannerAds: true,
        blockedAdvertiserAccountIds: [12, 34],
        consentedAt: '2026-05-17T00:00:00Z',
        unsubscribeTokenVersion: 1,
      },
    })
    const api = useAdPreferencesApi()
    const res = await api.updatePreferences({
      acceptAnnouncementAds: false,
      blockedAdvertiserAccountIds: [12, 34],
    })

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/ad-preferences', {
      method: 'PUT',
      body: {
        acceptAnnouncementAds: false,
        blockedAdvertiserAccountIds: [12, 34],
      },
    })
    expect(res.data.blockedAdvertiserAccountIds).toEqual([12, 34])
  })

  it('ADP-API-003: rotateUnsubscribeToken は PUT で rotateUnsubscribeToken=true を送る', async () => {
    mockFetch.mockResolvedValueOnce({
      data: {
        acceptAnnouncementAds: true,
        acceptEmailAds: true,
        acceptPushAds: true,
        acceptBannerAds: true,
        blockedAdvertiserAccountIds: [],
        consentedAt: null,
        unsubscribeTokenVersion: 2,
      },
    })
    const api = useAdPreferencesApi()
    const res = await api.rotateUnsubscribeToken()

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/ad-preferences', {
      method: 'PUT',
      body: { rotateUnsubscribeToken: true },
    })
    expect(res.data.unsubscribeTokenVersion).toBe(2)
  })
})
