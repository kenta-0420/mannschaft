import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F08.7 順位UI Wave B-3: useTournamentScorekeepers ユニットテスト。
 *
 * 検証観点（呼び出しパス・メソッド・body を実アサート）:
 *   SK-API-001: listScorekeepers → GET .../scorekeepers で data を返す
 *   SK-API-002: addScorekeeper → POST .../scorekeepers に { userId } body
 *   SK-API-003: removeScorekeeper → DELETE .../scorekeepers/{skId}
 *   SK-API-004: 失敗時は呼び出し側へ再 throw する（composable は握り潰さない）
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

// eslint-disable-next-line import/first
import { useTournamentScorekeepers } from '~/composables/tournament/useTournamentScorekeepers'

const ORG = 'org-public-1'
const T_ID = 555
const SK_ID = '0190abcd-1234-7000-8000-000000000001'

describe('useTournamentScorekeepers', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('SK-API-001: listScorekeepers は GET .../scorekeepers で data を返す', async () => {
    const list = [{ id: SK_ID, tournamentId: T_ID, userId: 9, createdBy: 1, createdAt: '2026-06-12T00:00:00' }]
    mockFetch.mockResolvedValueOnce({ data: list })
    const api = useTournamentScorekeepers()
    const res = await api.listScorekeepers(ORG, T_ID)

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/tournaments/${T_ID}/scorekeepers`,
    )
    expect(res.data).toEqual(list)
  })

  it('SK-API-002: addScorekeeper は POST .../scorekeepers に { userId } body を渡す', async () => {
    mockFetch.mockResolvedValueOnce({ data: { id: SK_ID, tournamentId: T_ID, userId: 42, createdBy: 1, createdAt: '2026-06-12T00:00:00' } })
    const api = useTournamentScorekeepers()
    await api.addScorekeeper(ORG, T_ID, 42)

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/tournaments/${T_ID}/scorekeepers`,
      { method: 'POST', body: { userId: 42 } },
    )
  })

  it('SK-API-003: removeScorekeeper は DELETE .../scorekeepers/{skId}', async () => {
    mockFetch.mockResolvedValueOnce(undefined)
    const api = useTournamentScorekeepers()
    await api.removeScorekeeper(ORG, T_ID, SK_ID)

    expect(mockFetch).toHaveBeenCalledWith(
      `/api/v1/organizations/${ORG}/tournaments/${T_ID}/scorekeepers/${SK_ID}`,
      { method: 'DELETE' },
    )
  })

  it('SK-API-004: 失敗時は再 throw する（握り潰さない）', async () => {
    mockFetch.mockRejectedValueOnce(new Error('boom'))
    const api = useTournamentScorekeepers()

    await expect(api.listScorekeepers(ORG, T_ID)).rejects.toThrow('boom')
  })
})
