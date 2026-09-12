// @vitest-environment node
// このテストはモックした useApi しか触らないため Nuxt ランタイムを必要としない。
// 既定の environment: 'nuxt' は 1 ファイルごとに Nuxt 環境を構築するため、
// 同時に重いビルドが走っている環境では setup フックが 120 秒でタイムアウトし
// テスト本体が 1 件も実行されない。不要な環境を要求しないことで確実に実行させる。
import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * useHourlyRateMemberPage ユニットテスト（CMP-260910-1555）。
 *
 * <h2>モックは実 API のレスポンス形状に合わせること【重要】</h2>
 * 本ファイルの初版は meta に `totalElements` を入れていたが、BE の
 * `com.mannschaft.app.common.PagedResponse.PageMeta` が実際に送るのは
 * `{ total, page, size, totalPages }` であり **`totalElements` は存在しない**。
 * 実装が `totalElements` を読んでいたため実行時は常に undefined となり、
 * 総件数が「そのページの件数（最大 100）」に落ちてページャーが永久に出ず、
 * 101 人目以降のメンバーの時給を設定できなかった。
 * **モックが誤った形をしていたせいでテストは実装の誤りをそのまま追認していた。**
 * 以下のモックは PagedResponse の実形状（`total` のみ・`totalElements` 無し）で書いてある。
 * 実装が `total` を読まなくなれば HRP-101 / HRP-006 が落ちる。
 *
 * 検証観点:
 *   HRP-001: 1 ページ表示につきメンバー一覧リクエストは 1 回（ページ総数に比例しない）
 *   HRP-002: 総ページ数が何ページであっても呼び出し回数は変わらない
 *   HRP-003: 時給は表示するメンバーぶんだけ引く
 *   HRP-004: 時給が無いメンバーは rate=null として残る
 *   HRP-005: 要求したページ番号とページサイズがそのままクエリに載る
 *   HRP-006: 総件数は meta.total（BE が実際に送る名前）から読む
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
    .filter(url => url.includes('/members?'))
}

function rateUrlCalls(): string[] {
  return mockFetch.mock.calls
    .map(call => String(call[0]))
    .filter(url => url.includes('/hourly-rate?'))
}

/**
 * メンバー一覧 1 ページぶんの応答を、BE の PagedResponse と同じ形で作る。
 *
 * meta は `{ total, page, size, totalPages }`。**`totalElements` は意図的に入れない**
 * （BE が送っていないため。ここに入れると実装の誤りを隠してしまう）。
 */
function membersPage(opts: {
  count: number
  page: number
  total: number
  startId?: number
}) {
  const startId = opts.startId ?? 1
  const totalPages = Math.max(1, Math.ceil(opts.total / MEMBER_PAGE_SIZE))
  return {
    data: Array.from({ length: opts.count }, (_, i) => ({
      userId: startId + i,
      displayName: `user${startId + i}`,
      avatarUrl: null,
      roleName: 'MEMBER',
      joinedAt: '2026-01-01T00:00:00',
    })),
    meta: { total: opts.total, page: opts.page, size: MEMBER_PAGE_SIZE, totalPages },
  }
}

function respondWith(
  members: ReturnType<typeof membersPage>,
  ratesByUserId: Record<number, unknown[]> = {},
) {
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
    respondWith(membersPage({ count: 100, page: 0, total: 5000 }))

    await useHourlyRateMemberPage().loadPage('team-alpha', '3', 0, '2026-06-15')

    // 是正前は全ページを取りにいくため totalPages 回（＝50 回）呼ばれていた
    expect(membersUrlCalls()).toHaveLength(1)
  })

  it('HRP-002: 総ページ数が 1 でも 50 でも呼び出し回数は変わらない（ページ数に比例しない）', async () => {
    respondWith(membersPage({ count: 100, page: 0, total: 100 }))
    await useHourlyRateMemberPage().loadPage('team-alpha', '3', 0, '2026-06-15')
    const callsForOnePage = membersUrlCalls().length

    mockFetch.mockReset()
    respondWith(membersPage({ count: 100, page: 0, total: 5000 }))
    await useHourlyRateMemberPage().loadPage('team-alpha', '3', 0, '2026-06-15')
    const callsForFiftyPages = membersUrlCalls().length

    expect(callsForOnePage).toBe(1)
    expect(callsForFiftyPages).toBe(1)
    expect(callsForFiftyPages).toBe(callsForOnePage)
  })

  it('HRP-003: 時給は表示するメンバーぶんだけ引く', async () => {
    respondWith(membersPage({ count: 30, page: 0, total: 5000 }))

    const result = await useHourlyRateMemberPage().loadPage('team-alpha', '3', 0, '2026-06-15')

    expect(result.rows).toHaveLength(30)
    // 総メンバー数（5000）ではなく、このページの 30 名ぶんだけ
    expect(rateUrlCalls()).toHaveLength(30)
  })

  it('HRP-004: 時給が無いメンバーは rate=null として一覧に残る', async () => {
    respondWith(membersPage({ count: 2, page: 0, total: 2 }), {
      1: [{ id: 9, userId: 1, teamId: 3, hourlyRate: '1200.00', effectiveFrom: '2026-04-01', createdAt: '' }],
    })

    const result = await useHourlyRateMemberPage().loadPage('team-alpha', '3', 0, '2026-06-15')

    expect(result.rows).toHaveLength(2)
    expect(result.rows[0]!.rate?.hourlyRate).toBe('1200.00')
    expect(result.rows[1]!.rate).toBeNull()
    expect(result.totalPages).toBe(1)
  })

  it('HRP-005: 要求したページ番号と BE 上限に一致するページサイズをクエリに載せる', async () => {
    respondWith(membersPage({ count: 100, page: 3, total: 5000 }))

    await useHourlyRateMemberPage().loadPage('team-alpha', '3', 3, '2026-06-15')

    expect(membersUrlCalls()[0]).toBe('/api/v1/teams/team-alpha/members?page=3&size=100')
  })

  it('HRP-006: 総件数は BE が実際に送る meta.total から読む（totalElements ではない）', async () => {
    // BE の PagedResponse が送るのは total のみ。totalElements は存在しない。
    respondWith(membersPage({ count: 100, page: 0, total: 250 }))

    const result = await useHourlyRateMemberPage().loadPage('team-alpha', '3', 0, '2026-06-15')

    // totalElements を読む実装だと undefined → members.length(=100) に落ちてここで落ちる
    expect(result.totalElements).toBe(250)
  })

  it('HRP-101: 101 人のチームでページャーが表示される（101 人目へ到達できる）', async () => {
    // 1 ページ目は 100 名ぶんしか返らない。101 人目に到達する唯一の手段がページャー。
    respondWith(membersPage({ count: 100, page: 0, total: 101 }))

    const result = await useHourlyRateMemberPage().loadPage('team-alpha', '3', 0, '2026-06-15')

    expect(result.rows).toHaveLength(100)
    expect(result.totalElements).toBe(101)
    expect(result.totalPages).toBe(2)
    // 是正前はここが false になり、101 人目の時給を永久に設定できなかった
    expect(shouldShowPaginator(result.totalElements)).toBe(true)
  })

  it('HRP-102: ちょうど 100 人ならページャーは出さない（境界）', async () => {
    respondWith(membersPage({ count: 100, page: 0, total: 100 }))

    const result = await useHourlyRateMemberPage().loadPage('team-alpha', '3', 0, '2026-06-15')

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
