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
 *   ORG-CTX-004: slug 一致が無ければ静かに null を返す（想定内・警告トーストを出さない）
 *   ORG-CTX-005: 親組織 organizationId が null でも単独チームのコンテキストを返す
 *
 * 通知方針（#1850「match警告トースト抑止」で確定）:
 *   「/me/teams に当該チームが無い」「親組織なし（単独チーム。DB 上 88%）」は想定内の正常状態のため
 *   warn を出さず null へ縮退する（ダッシュボードを開くたび警告が出る不具合の根治）。
 *   真のエラー（/me/teams 取得失敗）のみ warn する（ORG-CTX-006 で検証）。
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
import { useMatchOrgContext, parseOrgQuery, INVALID_ORG } from '~/composables/match/useMatchOrgContext'

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
    expect(result).toEqual({
      orgId: 10,
      teamId: 100,
      organizations: [{ id: 10, slug: '', name: '' }],
      orgInvalid: false,
    })
  })

  it('ORG-CTX-002: 同一 teamSlug の 2 回目はキャッシュを返し API を再度叩かない', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [{ id: 200, slug: TEAM_A_SLUG, organizationId: 20 }],
    })

    const { resolveContext } = useMatchOrgContext()
    const first = await resolveContext(TEAM_A_SLUG)
    const second = await resolveContext(TEAM_A_SLUG)

    expect(mockFetch).toHaveBeenCalledTimes(1)
    expect(first?.orgId).toBe(20)
    expect(second?.orgId).toBe(20)
    expect(first?.teamId).toBe(200)
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

    expect([ctxA?.orgId, ctxA?.teamId]).toEqual([30, 300])
    expect([ctxB?.orgId, ctxB?.teamId]).toEqual([40, 400])
  })

  it('ORG-CTX-004: slug 一致が無ければ静かに null を返す（警告トーストは出さない）', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [{ id: 100, slug: TEAM_A_SLUG, organizationId: 10 }],
    })

    const { resolveContext } = useMatchOrgContext()
    const result = await resolveContext('unknown-team-slug')

    expect(result).toBeNull()
    expect(mockWarn).not.toHaveBeenCalled()
  })

  it('ORG-CTX-005: 親組織 organizationId が null でも単独チームのコンテキストを返す', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [{ id: 100, slug: TEAM_A_SLUG, organizationId: null }],
    })

    const { resolveContext } = useMatchOrgContext()
    const result = await resolveContext(TEAM_A_SLUG)

    expect(result).toEqual({ orgId: null, teamId: 100, organizations: [], orgInvalid: false })
    expect(mockWarn).not.toHaveBeenCalled()
  })

  it('ORG-CTX-006: /me/teams の取得失敗は握り潰さず warn で通知する', async () => {
    mockFetch.mockRejectedValueOnce(new Error('network down'))

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
    expect(result).toEqual({
      orgId: 50,
      teamId: 500,
      teamSlug: TEAM_A_SLUG,
      organizations: [{ id: 50, slug: '', name: '' }],
      orgInvalid: false,
    })
  })

  it('ORG-CTX-102: 同一 teamId の 2 回目はキャッシュを返し API を再度叩かない', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [{ id: 600, slug: TEAM_B_SLUG, organizationId: 60 }],
    })

    const { resolveContextByTeamId } = useMatchOrgContext()
    const first = await resolveContextByTeamId(600)
    const second = await resolveContextByTeamId(600)

    expect(mockFetch).toHaveBeenCalledTimes(1)
    expect(first?.orgId).toBe(60)
    expect(second).toEqual(first)
  })

  it('ORG-CTX-103: 自分が所属しない teamId（記録権限なし）は静かに null を返す', async () => {
    mockFetch.mockResolvedValueOnce({
      data: [{ id: 700, slug: TEAM_A_SLUG, organizationId: 70 }],
    })

    const { resolveContextByTeamId } = useMatchOrgContext()
    const result = await resolveContextByTeamId(999)

    expect(result).toBeNull()
    expect(mockWarn).not.toHaveBeenCalled()
  })

  it('ORG-CTX-104: teamId 解決でも /me/teams 取得失敗は warn で通知する', async () => {
    mockFetch.mockRejectedValueOnce(new Error('network down'))

    const { resolveContextByTeamId } = useMatchOrgContext()
    const result = await resolveContextByTeamId(800)

    expect(result).toBeNull()
    expect(mockWarn).toHaveBeenCalled()
  })

  // ===== F01.2.1 §9.2 F1〜F4: 複数親組織（T が X と Y の両方に ACTIVE） =====

  const MULTI_PARENT_TEAM = {
    id: 900,
    slug: TEAM_A_SLUG,
    // BE 3-B: organizations は代表親組織が先頭。organizationId は互換のため代表親組織に固定。
    organizationId: 11,
    organizations: [
      { id: 11, slug: 'org-x', name: '組織X' },
      { id: 22, slug: 'org-y', name: '組織Y' },
    ],
  }

  it('ORG-CTX-201: 複数親組織なら全親組織を organizations で返し、未指定は代表親組織を使う', async () => {
    mockFetch.mockResolvedValueOnce({ data: [MULTI_PARENT_TEAM] })

    const { resolveContext } = useMatchOrgContext()
    const result = await resolveContext(TEAM_A_SLUG)

    expect(result?.orgId).toBe(11)
    expect(result?.organizations.map((o) => o.id)).toEqual([11, 22])
  })

  it('ORG-CTX-202: URL クエリ org で選んだ親組織（Y）を使い、/me/teams は1回しか引かない', async () => {
    mockFetch.mockResolvedValue({ data: [MULTI_PARENT_TEAM] })

    const { resolveContext } = useMatchOrgContext()
    const y = await resolveContext(TEAM_A_SLUG, { orgId: 22 })
    const x = await resolveContext(TEAM_A_SLUG, { orgId: 11 })

    expect(y?.orgId).toBe(22)
    expect(x?.orgId).toBe(11)
    expect(mockFetch).toHaveBeenCalledTimes(1)
  })

  it('ORG-CTX-203: チームの親組織でない org を指定したら代表親組織へ落とさず orgInvalid（候補は残す）', async () => {
    mockFetch.mockResolvedValueOnce({ data: [MULTI_PARENT_TEAM] })

    const { resolveContext } = useMatchOrgContext()
    const result = await resolveContext(TEAM_A_SLUG, { orgId: 999 })

    expect(result?.orgInvalid).toBe(true)
    expect(result?.orgId).toBeNull()
    // 正しい組織へ戻れるよう選択肢は残す（中2）
    expect(result?.organizations.map((o) => o.id)).toEqual([11, 22])
    expect(result?.teamId).toBe(900)
  })

  it('ORG-CTX-203b: 不正な org（org=abc）も代表親組織へ落とさず orgInvalid（高1）', async () => {
    mockFetch.mockResolvedValueOnce({ data: [MULTI_PARENT_TEAM] })

    const { resolveContext } = useMatchOrgContext()
    const result = await resolveContext(TEAM_A_SLUG, { orgId: parseOrgQuery('abc') })

    expect(result?.orgInvalid).toBe(true)
    expect(result?.orgId).toBeNull()
    expect(result?.organizations).toHaveLength(2)
  })

  it('ORG-CTX-203c: org 未指定のときだけ代表親組織を既定にする', async () => {
    mockFetch.mockResolvedValueOnce({ data: [MULTI_PARENT_TEAM] })

    const { resolveContext } = useMatchOrgContext()
    const result = await resolveContext(TEAM_A_SLUG, { orgId: parseOrgQuery(undefined) })

    expect(result?.orgInvalid).toBe(false)
    expect(result?.orgId).toBe(11)
  })

  it('ORG-CTX-204: 大会の組織 slug（ページの [slug]）の組織を使い、代表親組織ではない（F2）', async () => {
    mockFetch.mockResolvedValueOnce({ data: [MULTI_PARENT_TEAM] })

    const { resolveContextByTeamId } = useMatchOrgContext()
    const result = await resolveContextByTeamId(900, { orgSlug: 'org-y' })

    expect(result?.orgId).toBe(22)
    expect(result?.teamSlug).toBe(TEAM_A_SLUG)
  })

  it('ORG-CTX-205: 大会の組織にチームが加盟していなければ orgInvalid（チームの別の親組織で試合を作らない）', async () => {
    mockFetch.mockResolvedValueOnce({ data: [MULTI_PARENT_TEAM] })

    const { resolveContextByTeamId } = useMatchOrgContext()
    const result = await resolveContextByTeamId(900, { orgSlug: 'org-z' })

    expect(result?.orgInvalid).toBe(true)
    expect(result?.orgId).toBeNull()
  })

  it('ORG-CTX-206: repair-plan の組織 ID は数値（slug 文字列の id を number 判定して常に null になる旧バグの根治・G124）', async () => {
    mockFetch.mockResolvedValueOnce({ data: [MULTI_PARENT_TEAM] })

    const { resolveContext } = useMatchOrgContext()
    const result = await resolveContext(TEAM_A_SLUG, { orgId: 22 })

    expect(typeof result?.orgId).toBe('number')
  })

  it('ORG-CTX-207: parseOrgQuery は未指定だけ null・数値を orgId・不正値（空文字含む）を INVALID_ORG にする', () => {
    expect(parseOrgQuery('22')).toBe(22)
    expect(parseOrgQuery(['22', '33'])).toBe(22)
    expect(parseOrgQuery('abc')).toBe(INVALID_ORG)
    expect(parseOrgQuery('1e3')).toBe(INVALID_ORG)
    // `?org=`（空文字）は未指定ではなく不正値（代表親組織へ落とさない）
    expect(parseOrgQuery('')).toBe(INVALID_ORG)
    expect(parseOrgQuery([])).toBeNull()
    expect(parseOrgQuery(null)).toBeNull()
    expect(parseOrgQuery(undefined)).toBeNull()
  })
})
