import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * F08.10 useMatchAnalytics ユニットテスト
 *
 * 検証観点:
 *   ANL-API-001: getUserStats → GET /organizations/{orgId}/users/{userId}/match-stats に params
 *   ANL-API-002: getUserTimeline → GET .../users/{userId}/match-stats/timeline に params（meta あり）
 *   ANL-API-003: getUserTeamStats → GET .../users/{userId}/teams/{teamId}/match-stats に params
 *   ANL-API-004: getTeamStats → GET .../teams/{teamId}/match-stats に params
 *   ANL-API-005: 失敗時は notification.error を呼び再 throw する
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
import { useMatchAnalytics } from '~/composables/match/useMatchAnalytics'

const ORG = 7
const USER = 99
const TEAM = 42

describe('useMatchAnalytics', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockError.mockReset()
  })

  it('ANL-API-001: getUserStats は GET .../users/{userId}/match-stats に params を渡す', async () => {
    mockFetch.mockResolvedValueOnce({ data: { userId: USER, totalMatches: 3 } })
    const api = useMatchAnalytics()
    const res = await api.getUserStats(ORG, USER, { kind: 'LEAGUE', from: '2026-01-01' })

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/users/${USER}/match-stats`,
      { params: { kind: 'LEAGUE', from: '2026-01-01' } },
    )
    expect(res.totalMatches).toBe(3)
  })

  it('ANL-API-002: getUserTimeline は GET .../timeline に params を渡し meta を含むレスポンスを返す', async () => {
    mockFetch.mockResolvedValueOnce({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } })
    const api = useMatchAnalytics()
    const res = await api.getUserTimeline(ORG, USER, { page: 0, size: 20 })

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/users/${USER}/match-stats/timeline`,
      { params: { page: 0, size: 20 } },
    )
    expect(res.meta?.total).toBe(0)
  })

  it('ANL-API-003: getUserTeamStats は GET .../users/{userId}/teams/{teamId}/match-stats に params を渡す', async () => {
    mockFetch.mockResolvedValueOnce({ data: { userId: USER, totalMatches: 1 } })
    const api = useMatchAnalytics()
    await api.getUserTeamStats(ORG, USER, TEAM, { sport: 'SOCCER' })

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/users/${USER}/teams/${TEAM}/match-stats`,
      { params: { sport: 'SOCCER' } },
    )
  })

  it('ANL-API-004: getTeamStats は GET .../teams/{teamId}/match-stats に params を渡す', async () => {
    mockFetch.mockResolvedValueOnce({ data: { teamId: TEAM, totalMatches: 5 } })
    const api = useMatchAnalytics()
    const res = await api.getTeamStats(ORG, TEAM, { to: '2026-06-01' })

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/teams/${TEAM}/match-stats`,
      { params: { to: '2026-06-01' } },
    )
    expect(res.totalMatches).toBe(5)
  })

  it('ANL-API-005: 失敗時は notification.error を呼び再 throw する', async () => {
    mockFetch.mockRejectedValueOnce(new Error('boom'))
    const api = useMatchAnalytics()

    await expect(api.getTeamStats(ORG, TEAM)).rejects.toThrow('boom')
    expect(mockError).toHaveBeenCalledWith('match.analytics.error.load_failed')
  })
})
