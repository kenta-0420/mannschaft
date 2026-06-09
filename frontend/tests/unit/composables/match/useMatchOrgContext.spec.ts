import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F08.10 useMatchOrgContext ユニットテスト（F08.10 Phase3-C バグ修正検証）
 *
 * 検証観点:
 *   ORG-CTX-001: 未解決の teamId は getOrganizations を呼び orgId を返す
 *   ORG-CTX-002: 同一 teamId の 2 回目の呼び出しは API を叩かず（キャッシュ）同じ orgId を返す
 *   ORG-CTX-003: 別の teamId は別途 API を呼び出し正しい orgId を返す（チーム切替バグ根治）
 *   ORG-CTX-004: 組織が存在しない場合は null を返す
 */

const mockGetOrganizations = vi.fn()

vi.mock('~/composables/useTeamApi', () => ({
  useTeamApi: () => ({
    getOrganizations: mockGetOrganizations,
  }),
}))

// eslint-disable-next-line import/first
import { useMatchOrgContext } from '~/composables/match/useMatchOrgContext'

describe('useMatchOrgContext', () => {
  beforeEach(() => {
    mockGetOrganizations.mockReset()
  })

  it('ORG-CTX-001: 未解決の teamId は getOrganizations を呼び orgId を返す', async () => {
    mockGetOrganizations.mockResolvedValueOnce({ data: [{ id: 10 }] })

    const { resolveOrgId } = useMatchOrgContext()
    const result = await resolveOrgId('100')

    expect(mockGetOrganizations).toHaveBeenCalledTimes(1)
    expect(mockGetOrganizations).toHaveBeenCalledWith('100')
    expect(result).toBe(10)
  })

  it('ORG-CTX-002: 同一 teamId の 2 回目の呼び出しはキャッシュを返し API を 1 度しか呼ばない', async () => {
    mockGetOrganizations.mockResolvedValueOnce({ data: [{ id: 20 }] })

    const { resolveOrgId } = useMatchOrgContext()
    const first = await resolveOrgId('200')
    const second = await resolveOrgId('200')

    expect(mockGetOrganizations).toHaveBeenCalledTimes(1)
    expect(first).toBe(20)
    expect(second).toBe(20)
  })

  it('ORG-CTX-003: 別の teamId は新たに API を呼び正しい orgId を返す（チーム切替バグの根治確認）', async () => {
    // チーム A（org=30）→ チーム B（org=40）の順に切替える
    mockGetOrganizations
      .mockResolvedValueOnce({ data: [{ id: 30 }] }) // teamId='300'
      .mockResolvedValueOnce({ data: [{ id: 40 }] }) // teamId='400'

    const { resolveOrgId } = useMatchOrgContext()
    const orgForTeamA = await resolveOrgId('300')
    const orgForTeamB = await resolveOrgId('400')

    expect(mockGetOrganizations).toHaveBeenCalledTimes(2)
    expect(mockGetOrganizations).toHaveBeenNthCalledWith(1, '300')
    expect(mockGetOrganizations).toHaveBeenNthCalledWith(2, '400')
    // 旧実装では orgForTeamB = 30（チームAのorgIdを返し続けるバグ）。修正後は 40 を返す。
    expect(orgForTeamA).toBe(30)
    expect(orgForTeamB).toBe(40)
  })

  it('ORG-CTX-004: 組織が存在しない（data: []）場合は null を返す', async () => {
    mockGetOrganizations.mockResolvedValueOnce({ data: [] })

    const { resolveOrgId } = useMatchOrgContext()
    const result = await resolveOrgId('999')

    expect(result).toBeNull()
  })
})
