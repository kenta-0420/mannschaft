// @vitest-environment node
// このテストはモックした useApi しか触らないため Nuxt ランタイムを必要としない。
// 既定の environment: 'nuxt' は 1 ファイルごとに Nuxt 環境を構築するため、
// 同時に重いビルドが走っている環境では setup フックが 120 秒でタイムアウトし
// テスト本体が 1 件も実行されない。不要な環境を要求しないことで確実に実行させる。
import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * useHourlyRateMemberPage ユニットテスト（CMP-260912-1525）。
 *
 * <h2>モックは実 API のレスポンス形状に合わせること【重要】</h2>
 * 一括取得の 2 経路は BE の共通ラッパー
 * `com.mannschaft.app.common.ApiResponse` を使うので、body は **`{ data: [...] }` のみ**で
 * `meta` は付かない（ページング経路の `PagedResponse` とは別物）。
 * 以下のモックはその形で書いてある。自分の実装に合わせてモックを書くと欠陥を追認する。
 *
 * <h2>何を固定しているか</h2>
 * 以前は 1 ページずつサーバーに要求していた。`TeamService#getMembers` は
 * **1 ページ要求ごとに所属情報を全件走査**するため、全ページをめくると総走査量が
 * `N × ページ数` になる。現在は全員を 1 回で取り、時給も 1 回で取る。
 *
 * 検証観点:
 *   HRP-201: メンバー一覧の要求は 1 回（人数に関係なく一定）
 *   HRP-202: 時給の要求は 1 回（人数ぶん出ない ＝ 往復が N に比例しない）
 *   HRP-203: 人数を 20 倍にしても要求回数は変わらない
 *   HRP-204: 時給が無いメンバーは rate=null として一覧に残る
 *   HRP-205: 時給はユーザーIDで正しく突き合わされる（並び順に依存しない）
 *   HRP-206: 総件数はチーム全体の人数になる
 *   HRP-207: 一括取得のクエリが実 API の形（teamId・date）で載る
 *   HRP-101: 101 人のチームでページャーが表示される（101 人目へ到達できる）
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

// eslint-disable-next-line import/first
import {
  MEMBER_PAGE_SIZE,
  shouldShowPaginator,
  useHourlyRateMemberPage,
} from '~/composables/shift/useHourlyRateMemberPage'

function membersUrlCalls(): string[] {
  return mockFetch.mock.calls
    .map(call => String(call[0]))
    .filter(url => url.includes('/members'))
}

function rateUrlCalls(): string[] {
  return mockFetch.mock.calls
    .map(call => String(call[0]))
    .filter(url => url.includes('/hourly-rate'))
}

/** BE の `ApiResponse<List<ScopeMemberResponse>>` と同じ形（meta は無い）。 */
function membersResponse(count: number, startId = 1) {
  return {
    data: Array.from({ length: count }, (_, i) => ({
      userId: startId + i,
      displayName: `user${startId + i}`,
      avatarUrl: null,
      roleName: 'MEMBER',
      joinedAt: '2026-01-01T00:00:00',
    })),
  }
}

/** BE の `ApiResponse<List<HourlyRateResponse>>` と同じ形。未設定のユーザーは含まれない。 */
function ratesResponse(userIds: number[]) {
  return {
    data: userIds.map(userId => ({
      id: userId * 10,
      userId,
      teamId: 3,
      hourlyRate: '1200.00',
      effectiveFrom: '2026-04-01',
      createdAt: '2026-04-01T00:00:00',
    })),
  }
}

function respondWith(members: ReturnType<typeof membersResponse>, rateUserIds: number[] = []) {
  mockFetch.mockImplementation((url: string) => {
    if (String(url).includes('/members')) return Promise.resolve(members)
    if (String(url).includes('/hourly-rate')) return Promise.resolve(ratesResponse(rateUserIds))
    throw new Error(`想定外のリクエスト: ${url}`)
  })
}

describe('useHourlyRateMemberPage.loadAll', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('HRP-201/HRP-202: 何人いてもメンバー一覧 1 回・時給 1 回しか要求しない', async () => {
    respondWith(membersResponse(250), [1, 2, 3])

    const result = await useHourlyRateMemberPage().loadAll('team-alpha', '3', '2026-06-15')

    expect(result.rows).toHaveLength(250)
    // 是正前はページ数ぶん（250 名なら 3 回）メンバー一覧を引いていた
    expect(membersUrlCalls()).toHaveLength(1)
    // 是正前は 1 人 1 リクエスト（250 回）だった
    expect(rateUrlCalls()).toHaveLength(1)
  })

  it('HRP-203: 人数を 20 倍にしても要求回数は変わらない（総量が人数に比例しない）', async () => {
    respondWith(membersResponse(50))
    await useHourlyRateMemberPage().loadAll('team-alpha', '3', '2026-06-15')
    const callsForFifty = mockFetch.mock.calls.length

    mockFetch.mockReset()
    respondWith(membersResponse(1000))
    await useHourlyRateMemberPage().loadAll('team-alpha', '3', '2026-06-15')
    const callsForThousand = mockFetch.mock.calls.length

    expect(callsForFifty).toBe(2)
    expect(callsForThousand).toBe(2)
    expect(callsForThousand).toBe(callsForFifty)
  })

  it('HRP-204: 時給が無いメンバーは rate=null として一覧に残る', async () => {
    respondWith(membersResponse(2), [1])

    const result = await useHourlyRateMemberPage().loadAll('team-alpha', '3', '2026-06-15')

    expect(result.rows).toHaveLength(2)
    expect(result.rows[0]!.rate?.hourlyRate).toBe('1200.00')
    expect(result.rows[1]!.rate).toBeNull()
  })

  it('HRP-205: 時給はユーザーIDで突き合わせる（時給側の並び順・欠落に依存しない）', async () => {
    // 時給は 3 人目だけに設定されている。添字で突き合わせる実装だとここで落ちる。
    respondWith(membersResponse(3), [3])

    const result = await useHourlyRateMemberPage().loadAll('team-alpha', '3', '2026-06-15')

    expect(result.rows[0]!.rate).toBeNull()
    expect(result.rows[1]!.rate).toBeNull()
    expect(result.rows[2]!.rate?.userId).toBe(3)
  })

  it('HRP-206: 総件数はチーム全体の人数になる（未設定件数を全体の数として出せる）', async () => {
    respondWith(membersResponse(250), [1])

    const result = await useHourlyRateMemberPage().loadAll('team-alpha', '3', '2026-06-15')

    expect(result.totalElements).toBe(250)
    expect(result.rows.filter(r => r.rate === null)).toHaveLength(249)
  })

  it('HRP-207: 実 API の URL・クエリで要求する', async () => {
    respondWith(membersResponse(1))

    await useHourlyRateMemberPage().loadAll('team-alpha', '3', '2026-06-15')

    expect(membersUrlCalls()[0]).toBe('/api/v1/teams/team-alpha/members/all')
    expect(rateUrlCalls()[0]).toBe('/api/v1/shifts/hourly-rates?teamId=3&date=2026-06-15')
  })

  it('HRP-101: 101 人のチームでページャーが表示される（101 人目へ到達できる）', async () => {
    respondWith(membersResponse(101))

    const result = await useHourlyRateMemberPage().loadAll('team-alpha', '3', '2026-06-15')

    // 全員が手元にあるので、ページャーは取得済み配列の切り出しに使うだけ
    expect(result.rows).toHaveLength(101)
    expect(result.totalElements).toBe(101)
    expect(shouldShowPaginator(result.totalElements)).toBe(true)
  })

  it('HRP-102: ちょうど 100 人ならページャーは出さない（境界）', async () => {
    respondWith(membersResponse(100))

    const result = await useHourlyRateMemberPage().loadAll('team-alpha', '3', '2026-06-15')

    expect(result.totalElements).toBe(100)
    expect(shouldShowPaginator(result.totalElements)).toBe(false)
  })
})

describe('shouldShowPaginator', () => {
  it('HRP-103: 1 ページに収まらない件数でのみ true', () => {
    expect(shouldShowPaginator(0)).toBe(false)
    expect(shouldShowPaginator(MEMBER_PAGE_SIZE)).toBe(false)
    expect(shouldShowPaginator(MEMBER_PAGE_SIZE + 1)).toBe(true)
    expect(shouldShowPaginator(5000)).toBe(true)
  })
})
