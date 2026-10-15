import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F09.17 useAdPreferencesApi ユニットテスト
 *
 * 検証観点:
 *   ADP-API-001: getPreferences → GET /api/v1/me/ad-preferences を呼び {data} を返す
 *   ADP-API-002: updatePreferences → PUT で body を渡す
 *   ADP-API-003: rotateUnsubscribeTokens → PUT で rotateUnsubscribeTokens=true を送る
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useAdPreferencesApi } = await import('~/composables/useAdPreferencesApi')

function makePrefs(overrides: Record<string, unknown> = {}) {
  return {
    id: 'pref-1',
    acceptAnnouncementAds: true,
    acceptEmailAds: true,
    acceptPushAds: true,
    acceptBannerAds: true,
    blockedAdvertiserAccountIds: [],
    consentedAt: null,
    unsubscribeTokenVersion: 1,
    updatedAt: '2026-05-17T00:00:00Z',
    ...overrides,
  }
}

describe('useAdPreferencesApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('ADP-API-001: getPreferences は GET /api/v1/me/ad-preferences を呼ぶ', async () => {
    mockFetch.mockResolvedValueOnce({ data: makePrefs() })
    const api = useAdPreferencesApi()
    const res = await api.getPreferences()

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/ad-preferences')
    expect(res.data.acceptAnnouncementAds).toBe(true)
  })

  it('ADP-API-002: updatePreferences は PUT で body を渡す', async () => {
    mockFetch.mockResolvedValueOnce({
      data: makePrefs({
        acceptAnnouncementAds: false,
        blockedAdvertiserAccountIds: [12, 34],
      }),
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

  it('ADP-API-003: rotateUnsubscribeTokens は PUT で rotateUnsubscribeTokens=true を送る', async () => {
    mockFetch.mockResolvedValueOnce({
      data: makePrefs({ unsubscribeTokenVersion: 2 }),
    })
    const api = useAdPreferencesApi()
    const res = await api.rotateUnsubscribeTokens()

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/ad-preferences', {
      method: 'PUT',
      body: { rotateUnsubscribeTokens: true },
    })
    expect(res.data.unsubscribeTokenVersion).toBe(2)
  })
})
