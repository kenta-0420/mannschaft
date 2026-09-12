// @vitest-environment node
// このテストはモックした useApi しか触らないため Nuxt ランタイムを必要としない。
// 既定の environment: 'nuxt' は 1 ファイルごとに Nuxt 環境を構築するため、
// 同時に重いビルドが走っている環境では setup フックが 120 秒でタイムアウトし
// テスト本体が 1 件も実行されない。不要な環境を要求しないことで確実に実行させる。
import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * useHourlyRateMemberPage ユニットテスト（CMP-260910-1555 / 検分3巡目の是正）。
 *
 * 是正前は画面が全ページをまとめて（しかも並列で）取得していた。しかし
 * `TeamService#getMembers` はページ要求のたびに所属情報（user_roles + memberships）を
 * 全件走査して重複排除と優先度解決を行うため、総処理行数は
 * `N × ceil(N / ページサイズ)` となり人数の二乗のままだった。
 * 画面を 1 ページずつ表示する形に改め、1 回の表示で発行するメンバー一覧リクエストを
 * 常に 1 回に固定したことを、ここで機械的に押さえる。
 *
 * 検証観点:
 *   HRP-001: 1 ページ表示につきメンバー一覧リクエストは 1 回（ページ総数に比例しない）
 *   HRP-002: 総ページ数が何ページであっても呼び出し回数は変わらない
 *   HRP-003: 時給は表示するメンバーぶんだけ引く（未取得メンバーのぶんは引かない）
 *   HRP-004: 時給が無いメンバーは rate=null として残る（一覧から消えない）
 *   HRP-005: 要求したページ番号とページサイズがそのままクエリに載る
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

// eslint-disable-next-line import/first
import { useHourlyRateMemberPage } from '~/composables/shift/useHourlyRateMemberPage'

interface Call { url: string }

function membersUrlCalls(): Call[] {
  return mockFetch.mock.calls
    .map(call => ({ url: String(call[0]) }))
    .filter(c => c.url.includes('/members?'))
}

function rateUrlCalls(): Call[] {
  return mockFetch.mock.calls
    .map(call => ({ url: String(call[0]) }))
    .filter(c => c.url.includes('/hourly-rate?'))
}

/** メンバー一覧 1 ページぶんの応答を作る。 */
function membersPage(count: number, page: number, totalPages: number, startId = 1) {
  return {
    data: Array.from({ length: count }, (_, i) => ({
      userId: startId + i,
      displayName: `user${startId + i}`,
      avatarUrl: null,
      roleName: 'MEMBER',
      joinedAt: '2026-01-01T00:00:00',
    })),
    meta: { page, size: 100, totalElements: totalPages * 100, totalPages },
  }
}

/** useApi のモックを「メンバー一覧 → 以降は時給」の順で応答させる。 */
function respondWith(members: ReturnType<typeof membersPage>, ratesByUserId: Record<number, unknown[]> = {}) {
  mockFetch.mockImplementation((url: string) => {
    if (String(url).includes('/members?')) return Promise.resolve(members)
    const matched = /userId=(\d+)/.exec(String(url))
    const userId = matched ? Number(matched[1]) : -1
    return Promise.resolve({ data: ratesByUserId[userId] ?? [] })
  })
}

describe('useHourlyRateMemberPage.loadPage', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('HRP-001: 1 ページ表示につきメンバー一覧リクエストは 1 回だけ', async () => {
    respondWith(membersPage(100, 0, 50))

    await useHourlyRateMemberPage().loadPage('team-alpha', '3', 0, '2026-06-15')

    // 是正前は全ページを取りにいくため totalPages 回（＝50 回）呼ばれていた
    expect(membersUrlCalls()).toHaveLength(1)
  })

  it('HRP-002: 総ページ数が 1 でも 50 でも呼び出し回数は変わらない（ページ数に比例しない）', async () => {
    respondWith(membersPage(100, 0, 1))
    await useHourlyRateMemberPage().loadPage('team-alpha', '3', 0, '2026-06-15')
    const callsForOnePage = membersUrlCalls().length

    mockFetch.mockReset()
    respondWith(membersPage(100, 0, 50))
    await useHourlyRateMemberPage().loadPage('team-alpha', '3', 0, '2026-06-15')
    const callsForFiftyPages = membersUrlCalls().length

    expect(callsForOnePage).toBe(1)
    expect(callsForFiftyPages).toBe(1)
    expect(callsForFiftyPages).toBe(callsForOnePage)
  })

  it('HRP-003: 時給は表示するメンバーぶんだけ引く', async () => {
    respondWith(membersPage(30, 0, 50))

    const result = await useHourlyRateMemberPage().loadPage('team-alpha', '3', 0, '2026-06-15')

    expect(result.rows).toHaveLength(30)
    // 総メンバー数（5000）ではなく、このページの 30 名ぶんだけ
    expect(rateUrlCalls()).toHaveLength(30)
  })

  it('HRP-004: 時給が無いメンバーは rate=null として一覧に残る', async () => {
    respondWith(membersPage(2, 0, 1), {
      1: [{ id: 9, userId: 1, teamId: 3, hourlyRate: '1200.00', effectiveFrom: '2026-04-01', createdAt: '' }],
    })

    const result = await useHourlyRateMemberPage().loadPage('team-alpha', '3', 0, '2026-06-15')

    expect(result.rows).toHaveLength(2)
    expect(result.rows[0]!.rate?.hourlyRate).toBe('1200.00')
    expect(result.rows[1]!.rate).toBeNull()
    expect(result.totalPages).toBe(1)
  })

  it('HRP-005: 要求したページ番号と BE 上限に一致するページサイズをクエリに載せる', async () => {
    respondWith(membersPage(100, 3, 50))

    await useHourlyRateMemberPage().loadPage('team-alpha', '3', 3, '2026-06-15')

    expect(membersUrlCalls()[0]!.url)
      .toBe('/api/v1/teams/team-alpha/members?page=3&size=100')
  })
})
