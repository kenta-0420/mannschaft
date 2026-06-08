import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * F08.10 useMatchEventApi ユニットテスト
 *
 * 検証観点:
 *   EVT-API-001: listEvents → GET /organizations/{orgId}/matches/{matchId}/events で data を返す
 *   EVT-API-002: listAppearances → GET .../appearances で data を返す
 *   EVT-API-003: addEvent → POST .../events に body
 *   EVT-API-004: updateEvent → PATCH .../events/{eventId} に body
 *   EVT-API-005: deleteEvent → DELETE .../events/{eventId}
 *   EVT-API-006: 失敗時は notification.error を呼び再 throw する
 */

const mockFetch = vi.fn()
const mockError = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ error: mockError, success: vi.fn() }),
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

// eslint-disable-next-line import/first
import { useMatchEventApi } from '~/composables/match/useMatchEventApi'

const ORG = 7
const MATCH = 'm-uuid-1'
const EVENT = 'e-uuid-1'

describe('useMatchEventApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockError.mockReset()
  })

  it('EVT-API-001: listEvents は GET .../events で data を返す', async () => {
    mockFetch.mockResolvedValueOnce({ data: { events: [], scoreMismatch: false } })
    const api = useMatchEventApi()
    const res = await api.listEvents(ORG, MATCH)

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/matches/${MATCH}/events`,
    )
    expect(res.scoreMismatch).toBe(false)
  })

  it('EVT-API-002: listAppearances は GET .../appearances で data を返す', async () => {
    mockFetch.mockResolvedValueOnce({ data: [{ id: 'a1' }] })
    const api = useMatchEventApi()
    const res = await api.listAppearances(ORG, MATCH)

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/matches/${MATCH}/appearances`,
    )
    expect(res).toEqual([{ id: 'a1' }])
  })

  it('EVT-API-003: addEvent は POST .../events に body を渡す', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: EVENT } })
    const api = useMatchEventApi()
    const body = {
      eventType: 'GOAL' as const,
      period: 'FIRST_HALF' as const,
      teamSide: 'HOME' as const,
      minute: 12,
    }
    await api.addEvent(ORG, MATCH, body)

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/matches/${MATCH}/events`,
      { method: 'POST', body },
    )
  })

  it('EVT-API-004: updateEvent は PATCH .../events/{eventId} に body を渡す', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: EVENT } })
    const api = useMatchEventApi()
    const body = {
      eventType: 'GOAL' as const,
      period: 'SECOND_HALF' as const,
      teamSide: 'HOME' as const,
      minute: 70,
    }
    await api.updateEvent(ORG, MATCH, EVENT, body)

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/matches/${MATCH}/events/${EVENT}`,
      { method: 'PATCH', body },
    )
  })

  it('EVT-API-005: deleteEvent は DELETE .../events/{eventId}', async () => {
    mockFetch.mockResolvedValueOnce(undefined)
    const api = useMatchEventApi()
    await api.deleteEvent(ORG, MATCH, EVENT)

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/matches/${MATCH}/events/${EVENT}`,
      { method: 'DELETE' },
    )
  })

  it('EVT-API-006: 失敗時は notification.error を呼び再 throw する', async () => {
    mockFetch.mockRejectedValueOnce(new Error('boom'))
    const api = useMatchEventApi()

    await expect(api.listEvents(ORG, MATCH)).rejects.toThrow('boom')
    expect(mockError).toHaveBeenCalledWith('match.live.error.load_events_failed')
  })
})
