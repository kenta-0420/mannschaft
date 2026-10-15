import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F09.17 残課題 4 — useAdPreferencesApi.submitUnsubscribe ユニットテスト
 *
 * 検証観点:
 *   AP-UNSUB-001: submitUnsubscribe → POST /api/v1/ads/unsubscribe に token+channels を渡す
 *   AP-UNSUB-002: レスポンス JSON をそのまま返却する
 *   AP-UNSUB-003: 既存 rotateUnsubscribeTokens / getPreferences は影響を受けない
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useAdPreferencesApi } = await import('~/composables/useAdPreferencesApi')

describe('useAdPreferencesApi.submitUnsubscribe', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('AP-UNSUB-001: POST /api/v1/ads/unsubscribe に token+channels を渡す', async () => {
    mockFetch.mockResolvedValueOnce({
      disabledChannels: ['EMAIL', 'PUSH'],
      remainingActiveChannels: ['ANNOUNCEMENT', 'BANNER'],
      messageKey: 'advertising.unsubscribe_spa.success_message',
    })
    const api = useAdPreferencesApi()
    await api.submitUnsubscribe({
      token: 'jwt-xyz',
      channels: ['EMAIL', 'PUSH'],
    })

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/ads/unsubscribe', {
      method: 'POST',
      body: {
        token: 'jwt-xyz',
        channels: ['EMAIL', 'PUSH'],
      },
    })
  })

  it('AP-UNSUB-002: レスポンス JSON を呼び出し元にそのまま返す', async () => {
    const expected = {
      disabledChannels: ['EMAIL'],
      remainingActiveChannels: ['ANNOUNCEMENT', 'PUSH', 'BANNER'],
      messageKey: 'advertising.unsubscribe_spa.success_message',
    }
    mockFetch.mockResolvedValueOnce(expected)
    const api = useAdPreferencesApi()

    const result = await api.submitUnsubscribe({
      token: 'jwt',
      channels: ['EMAIL'],
    })

    expect(result).toEqual(expected)
  })

  it('AP-UNSUB-003: getPreferences は既存通り GET /api/v1/me/ad-preferences', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: 'p-1' } })
    const api = useAdPreferencesApi()
    await api.getPreferences()

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/ad-preferences')
  })

  it('AP-UNSUB-004: rotateUnsubscribeTokens は既存通り PUT /api/v1/me/ad-preferences', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: 'p-1' } })
    const api = useAdPreferencesApi()
    await api.rotateUnsubscribeTokens()

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/ad-preferences', {
      method: 'PUT',
      body: { rotateUnsubscribeTokens: true },
    })
  })
})
