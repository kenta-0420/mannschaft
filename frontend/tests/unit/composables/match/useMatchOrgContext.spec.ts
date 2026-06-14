import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * F08.10 useMatchOrgContext ユニットテスト（識別子契約バグの根治検証）。
 *
 * 新契約: slug → 数値 orgId ＋ 数値 teamId を /me/teams 単一 API で解決する
 * （MyTeamResponse が id 数値・slug・organizationId 数値を持つ）。
 *
 * 検証観点:
 *   ORG-CTX-001: 未解決の teamSlug は /me/teams から {orgId, teamId}（数値）を返す
 *   ORG-CTX-002: 同一 teamSlug の 2 回目はキャッシュを返し API を再度叩かない
 *   ORG-CTX-003: 別の teamSlug は正しい数値を返す（チーム切替バグ根治）
 *   ORG-CTX-004: slug 一致が無ければ null を返し通知する（解決不能）
 *   ORG-CTX-005: 親組織 organizationId が null なら null を返し通知する
 */

const mockFetch = vi.fn()
const mockWarn = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ warn: mockWarn, error: vi.fn(), success: vi.fn(), info: vi.fn() }),
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

// eslint-disable-next-line import/first
import { useMatchOrgContext } from '~/composables/match/useMatchOrgContext'

// slug（URL slug 文字列）。数値 id とは別物であることを表現するための固定値。
const TEAM_A_SLUG = 'team-alpha'
const TEAM_B_SLUG = 'team-bravo'

describe('useMatchOrgContext', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockWarn.mockReset()
  })

  it('ORG-CTX-001: slug から数値 orgId/teamId を /me/teams で解決する', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [{ id: 100, slug: TEAM_A_SLUG, organizationId: 10 }],
    })

    const { resolveContext } = useMatchOrgContext()
    const result = await resolveContext(TEAM_A_SLUG)

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/teams')
    expect(result).toEqual({ orgId: 10, teamId: 100 })
  })

  it('ORG-CTX-002: 同一 teamSlug の 2 回目はキャッシュを返し API を再度叩かない', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [{ id: 200, slug: TEAM_A_SLUG, organizationId: 20 }],
    })

    const { resolveContext } = useMatchOrgContext()
    const first = await resolveContext(TEAM_A_SLUG)
    const second = await resolveContext(TEAM_A_SLUG)

    expect(mockFetch).toHaveBeenCalledTimes(1)
    expect(first).toEqual({ orgId: 20, teamId: 200 })
    expect(second).toEqual({ orgId: 20, teamId: 200 })
  })

  it('ORG-CTX-003: 別の teamSlug は正しい数値を返す（チーム切替バグ根治）', async () => {
    const payload = {
      data: [
        { id: 300, slug: TEAM_A_SLUG, organizationId: 30 },
        { id: 400, slug: TEAM_B_SLUG, organizationId: 40 },
      ],
    }
    mockFetch.mockResolvedValue(payload)

    const { resolveContext } = useMatchOrgContext()
    const ctxA = await resolveContext(TEAM_A_SLUG)
    const ctxB = await resolveContext(TEAM_B_SLUG)

    expect(ctxA).toEqual({ orgId: 30, teamId: 300 })
    expect(ctxB).toEqual({ orgId: 40, teamId: 400 })
  })

  it('ORG-CTX-004: slug 一致が無ければ null を返し通知する', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [{ id: 100, slug: TEAM_A_SLUG, organizationId: 10 }],
    })

    const { resolveContext } = useMatchOrgContext()
    const result = await resolveContext('unknown-team-slug')

    expect(result).toBeNull()
    expect(mockWarn).toHaveBeenCalled()
  })

  it('ORG-CTX-005: 親組織 organizationId が null なら null を返し通知する', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [{ id: 100, slug: TEAM_A_SLUG, organizationId: null }],
    })

    const { resolveContext } = useMatchOrgContext()
    const result = await resolveContext(TEAM_A_SLUG)

    expect(result).toBeNull()
    expect(mockWarn).toHaveBeenCalled()
  })

  // ===== 入口①: 数値 teamId 起点の解決（resolveContextByTeamId） =====

  it('ORG-CTX-101: 数値 teamId から orgId/teamId/teamSlug を解決する', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [{ id: 500, slug: TEAM_A_SLUG, organizationId: 50 }],
    })

    const { resolveContextByTeamId } = useMatchOrgContext()
    const result = await resolveContextByTeamId(500)

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/teams')
    expect(result).toEqual({ orgId: 50, teamId: 500, teamSlug: TEAM_A_SLUG })
  })

  it('ORG-CTX-102: 同一 teamId の 2 回目はキャッシュを返し API を再度叩かない', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [{ id: 600, slug: TEAM_B_SLUG, organizationId: 60 }],
    })

    const { resolveContextByTeamId } = useMatchOrgContext()
    const first = await resolveContextByTeamId(600)
    const second = await resolveContextByTeamId(600)

    expect(mockFetch).toHaveBeenCalledTimes(1)
    expect(first).toEqual({ orgId: 60, teamId: 600, teamSlug: TEAM_B_SLUG })
    expect(second).toEqual(first)
  })

  it('ORG-CTX-103: 自分が所属しない teamId（記録権限なし）は null を返し通知する', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [{ id: 700, slug: TEAM_A_SLUG, organizationId: 70 }],
    })

    const { resolveContextByTeamId } = useMatchOrgContext()
    const result = await resolveContextByTeamId(999)

    expect(result).toBeNull()
    expect(mockWarn).toHaveBeenCalled()
  })
})
