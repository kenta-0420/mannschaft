import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * F08.10 useMatchApi ユニットテスト
 *
 * 検証観点（呼び出しパス・メソッド・params/body を実アサート）:
 *   MATCH-API-001: listMatches → GET /organizations/{orgId}/teams/{teamId}/matches に params
 *   MATCH-API-002: getMatch → GET .../matches/{matchId} で data を返す
 *   MATCH-API-003: createMatch → POST .../matches に body
 *   MATCH-API-004: updateMatch → PATCH .../matches/{matchId} に body
 *   MATCH-API-005: changeStatus → PATCH .../matches/{matchId}/status に body
 *   MATCH-API-006: finalizeScore → PATCH .../matches/{matchId}/score に body
 *   MATCH-API-007: changeRecordingMode → PATCH .../matches/{matchId}/recording-mode
 *   MATCH-API-008: deleteMatch → DELETE .../matches/{matchId}
 *   MATCH-API-009: 失敗時は notification.error を呼び再 throw する
 */

const mockFetch = vi.fn()
const mockError = vi.fn()
const mockSuccess = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ error: mockError, success: mockSuccess }),
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

// eslint-disable-next-line import/first
import { useMatchApi } from '~/composables/match/useMatchApi'

const ORG = 7
const TEAM = 42
const MATCH = 'm-uuid-1'

describe('useMatchApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockError.mockReset()
    mockSuccess.mockReset()
  })

  it('MATCH-API-001: listMatches は GET .../matches に params を渡す', async () => {
    mockFetch.mockResolvedValueOnce({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } })
    const api = useMatchApi()
    const res = await api.listMatches(ORG, TEAM, { kind: 'PRACTICE', status: 'COMPLETED', page: 1, size: 20 })

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/teams/${TEAM}/matches`,
      { params: { kind: 'PRACTICE', status: 'COMPLETED', page: 1, size: 20 } },
    )
    expect(res.meta?.total).toBe(0)
  })

  it('MATCH-API-002: getMatch は GET .../matches/{matchId} で data を返す', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: 1 } })
    const api = useMatchApi()
    const res = await api.getMatch(ORG, TEAM, MATCH)

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/teams/${TEAM}/matches/${MATCH}`,
    )
    expect(res).toEqual({ id: 1 })
  })

  it('MATCH-API-003: createMatch は POST .../matches に body を渡す', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: 1 } })
    const api = useMatchApi()
    const body = { kind: 'FRIENDLY' as const, opponentName: '相手' }
    await api.createMatch(ORG, TEAM, body)

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/teams/${TEAM}/matches`,
      { method: 'POST', body },
    )
    expect(mockSuccess).toHaveBeenCalledWith('match.create.success')
  })

  it('MATCH-API-004: updateMatch は PATCH .../matches/{matchId} に body を渡す', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: 1 } })
    const api = useMatchApi()
    const body = { venue: '市民グラウンド' }
    await api.updateMatch(ORG, TEAM, MATCH, body)

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/teams/${TEAM}/matches/${MATCH}`,
      { method: 'PATCH', body },
    )
  })

  it('MATCH-API-005: changeStatus は PATCH .../matches/{matchId}/status に body を渡す', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: 1 } })
    const api = useMatchApi()
    await api.changeStatus(ORG, TEAM, MATCH, { status: 'IN_PROGRESS' })

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/teams/${TEAM}/matches/${MATCH}/status`,
      { method: 'PATCH', body: { status: 'IN_PROGRESS' } },
    )
  })

  it('MATCH-API-006: finalizeScore は PATCH .../matches/{matchId}/score に body を渡す', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: 1 } })
    const api = useMatchApi()
    await api.finalizeScore(ORG, TEAM, MATCH, { homeScore: 2, awayScore: 1 })

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/teams/${TEAM}/matches/${MATCH}/score`,
      { method: 'PATCH', body: { homeScore: 2, awayScore: 1 } },
    )
  })

  it('MATCH-API-007: changeRecordingMode は PATCH .../recording-mode に body を渡す', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: 1 } })
    const api = useMatchApi()
    await api.changeRecordingMode(ORG, TEAM, MATCH, { hasScorekeeper: true, scorekeeperUserId: 9 })

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/teams/${TEAM}/matches/${MATCH}/recording-mode`,
      { method: 'PATCH', body: { hasScorekeeper: true, scorekeeperUserId: 9 } },
    )
  })

  it('MATCH-API-008: deleteMatch は DELETE .../matches/{matchId}', async () => {
    mockFetch.mockResolvedValueOnce(undefined)
    const api = useMatchApi()
    await api.deleteMatch(ORG, TEAM, MATCH)

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/teams/${TEAM}/matches/${MATCH}`,
      { method: 'DELETE' },
    )
    expect(mockSuccess).toHaveBeenCalledWith('match.list.delete_success')
  })

  it('MATCH-API-010: resolveMatchBySchedule は GET .../matches/by-schedule/{scheduleId} で既存サマリを返す', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: 'm-1' } })
    const api = useMatchApi()
    const res = await api.resolveMatchBySchedule(ORG, TEAM, 555)

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/teams/${TEAM}/matches/by-schedule/555`,
    )
    expect(res).toEqual({ id: 'm-1' })
  })

  it('MATCH-API-011: resolveMatchBySchedule は data:null（既存なし）で null を返す', async () => {
    mockFetch.mockResolvedValueOnce({ data: null })
    const api = useMatchApi()
    const res = await api.resolveMatchBySchedule(ORG, TEAM, 556)

    expect(res).toBeNull()
  })

  it('MATCH-API-009: 失敗時は notification.error を呼び再 throw する', async () => {
    mockFetch.mockRejectedValueOnce(new Error('boom'))
    const api = useMatchApi()

    await expect(api.listMatches(ORG, TEAM)).rejects.toThrow('boom')
    expect(mockError).toHaveBeenCalledWith('match.list.error.load_failed')
  })
})
