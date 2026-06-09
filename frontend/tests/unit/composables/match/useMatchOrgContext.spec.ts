import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * F08.10 useMatchOrgContext ユニットテスト（識別子契約バグの根治検証）。
 *
 * 新契約: publicId(UUID) → 数値 orgId ＋ 数値 teamId を /me/teams 単一 API で解決する
 * （MyTeamResponse が id 数値・publicId・organizationId 数値を持つ）。
 *
 * 検証観点:
 *   ORG-CTX-001: 未解決の teamPublicId は /me/teams から {orgId, teamId}（数値）を返す
 *   ORG-CTX-002: 同一 teamPublicId の 2 回目はキャッシュを返し API を再度叩かない
 *   ORG-CTX-003: 別の teamPublicId は正しい数値を返す（チーム切替バグ根治）
 *   ORG-CTX-004: publicId 一致が無ければ null を返し通知する（解決不能）
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

// publicId（UUID）。数値 id とは別物であることを表現するための固定値。
const TEAM_A_PUBLIC = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const TEAM_B_PUBLIC = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'

describe('useMatchOrgContext', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockWarn.mockReset()
  })

  it('ORG-CTX-001: publicId から数値 orgId/teamId を /me/teams で解決する', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [{ id: 100, publicId: TEAM_A_PUBLIC, organizationId: 10 }],
    })

    const { resolveContext } = useMatchOrgContext()
    const result = await resolveContext(TEAM_A_PUBLIC)

    expect(mockFetch).toHaveBeenCalledWith('/api/v1/me/teams')
    expect(result).toEqual({ orgId: 10, teamId: 100 })
  })

  it('ORG-CTX-002: 同一 teamPublicId の 2 回目はキャッシュを返し API を再度叩かない', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [{ id: 200, publicId: TEAM_A_PUBLIC, organizationId: 20 }],
    })

    const { resolveContext } = useMatchOrgContext()
    const first = await resolveContext(TEAM_A_PUBLIC)
    const second = await resolveContext(TEAM_A_PUBLIC)

    expect(mockFetch).toHaveBeenCalledTimes(1)
    expect(first).toEqual({ orgId: 20, teamId: 200 })
    expect(second).toEqual({ orgId: 20, teamId: 200 })
  })

  it('ORG-CTX-003: 別の teamPublicId は正しい数値を返す（チーム切替バグ根治）', async () => {
    const payload = {
      data: [
        { id: 300, publicId: TEAM_A_PUBLIC, organizationId: 30 },
        { id: 400, publicId: TEAM_B_PUBLIC, organizationId: 40 },
      ],
    }
    mockFetch.mockResolvedValue(payload)

    const { resolveContext } = useMatchOrgContext()
    const ctxA = await resolveContext(TEAM_A_PUBLIC)
    const ctxB = await resolveContext(TEAM_B_PUBLIC)

    expect(ctxA).toEqual({ orgId: 30, teamId: 300 })
    expect(ctxB).toEqual({ orgId: 40, teamId: 400 })
  })

  it('ORG-CTX-004: publicId 一致が無ければ null を返し通知する', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [{ id: 100, publicId: TEAM_A_PUBLIC, organizationId: 10 }],
    })

    const { resolveContext } = useMatchOrgContext()
    const result = await resolveContext('zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz')

    expect(result).toBeNull()
    expect(mockWarn).toHaveBeenCalled()
  })

  it('ORG-CTX-005: 親組織 organizationId が null なら null を返し通知する', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [{ id: 100, publicId: TEAM_A_PUBLIC, organizationId: null }],
    })

    const { resolveContext } = useMatchOrgContext()
    const result = await resolveContext(TEAM_A_PUBLIC)

    expect(result).toBeNull()
    expect(mockWarn).toHaveBeenCalled()
  })
})
