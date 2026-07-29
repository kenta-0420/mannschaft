import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { AnnouncementFeedItem, AnnouncementFeedResponse } from '~/types/announcement'

/**
 * issue #2495: お知らせ一覧を開いたまま放置し、その間に対象が期限切れ・削除になった後で
 * クリックすると「何も起こらずに死ぬ」問題の回帰テスト。
 *
 * 従来は `markAsRead()` の例外が呼び出し元の `navigateTo` の手前で抜けてしまい、
 * 画面上は「クリックしても何も起きない」状態になっていた。
 *
 * 御裁可（候補1「一覧から消して知らせる」）に沿って、
 * 「もう見えない（ANNOUNCE_001）」と「通信に失敗した」を区別することを検証する。
 *
 * テストケース一覧:
 *  ANN-2495-001: 未読 → 既読成功なら true を返し、遷移を続行させる
 *  ANN-2495-002: 既読済みなら API を叩かずに true を返す
 *  ANN-2495-003: ANNOUNCE_001 なら false を返し、一覧から取り除いてトーストで知らせる
 *  ANN-2495-004: ANNOUNCE_001 で取り除いた際、未読カウントも 1 減る
 *  ANN-2495-005: 通信失敗（エラーコード無し）では項目を消さず、true を返して遷移は続行する
 *  ANN-2495-006: 通信失敗ではエラーを握りつぶさず handleApiError に渡す（#2460）
 *  ANN-2495-007: isAnnouncementGoneError — ANNOUNCE_001 のみ true（他コード・素の Error は false）
 */

// ============================================================
// Nuxt auto-import / composable のモック
// ============================================================
const mockFetch = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const tMock = vi.fn((key: string): string => key)
mockNuxtImport('useI18n', () => () => ({ t: tMock, te: () => true }))

const mockNotifyWarn = vi.fn()
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: vi.fn(),
    info: vi.fn(),
    warn: mockNotifyWarn,
    error: vi.fn(),
  }),
}))

const mockHandleApiError = vi.fn()
vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({
    handleApiError: mockHandleApiError,
    handleError: mockHandleApiError,
    resolveMessage: vi.fn(),
    getFieldErrors: vi.fn(() => ({})),
  }),
}))

// テスト対象（モック設定後に動的 import。@nuxt/test-utils の hoisting に依存するため
// import/first の ESLint ルールを無効化する）
// eslint-disable-next-line import/first
import { useAnnouncementFeed, isAnnouncementGoneError } from '~/composables/useAnnouncementFeed'

// ============================================================
// フィクスチャ
// ============================================================
function buildItem(overrides: Partial<AnnouncementFeedItem> = {}): AnnouncementFeedItem {
  return {
    id: 1,
    scopeType: 'TEAM',
    scopeId: 'my-team',
    sourceType: 'BLOG_POST',
    sourceId: 100,
    sourceUrl: '/blog/posts/100',
    title: 'お知らせタイトル',
    excerpt: null,
    priority: 'NORMAL',
    isPinned: false,
    pinnedAt: null,
    visibility: 'PUBLIC',
    author: null,
    sourceMeta: null,
    isRead: false,
    startsAt: null,
    expiresAt: null,
    createdAt: '2026-07-01T00:00:00Z',
    ...overrides,
  }
}

function buildResponse(items: AnnouncementFeedItem[], unreadCount: number): AnnouncementFeedResponse {
  return {
    data: items,
    meta: {
      nextCursor: null,
      limit: 20,
      unreadCount,
      totalCount: items.length,
      hasNext: false,
    },
  }
}

/** BE が返すエラー形状（ofetch の FetchError は body を `data` に載せる） */
function apiError(code: string) {
  return Object.assign(new Error(`api error ${code}`), {
    data: { error: { code, message: 'お知らせが見つかりません' } },
    statusCode: 400,
  })
}

/**
 * feed / meta を投入済みの composable を返す。
 * fetchFeed 経由でしか feed は埋まらないため、初回ロードを 1 回踏ませる。
 */
async function setupFeed(items: AnnouncementFeedItem[], unreadCount: number) {
  mockFetch.mockResolvedValueOnce(buildResponse(items, unreadCount))
  const feedApi = useAnnouncementFeed('TEAM', 'my-team')
  await feedApi.fetchFeed({ limit: 20 })
  return feedApi
}

beforeEach(() => {
  mockFetch.mockReset()
  mockNotifyWarn.mockReset()
  mockHandleApiError.mockReset()
  tMock.mockClear()
})

describe('useAnnouncementFeed — markAsReadBeforeOpen（#2495）', () => {
  // ──────────────────────────────────────────────
  // ANN-2495-001 / 002: 正常系
  // ──────────────────────────────────────────────

  it('ANN-2495-001: 未読 → 既読成功なら true（遷移続行）', async () => {
    const item = buildItem({ id: 7, isRead: false })
    const feedApi = await setupFeed([item], 1)
    mockFetch.mockResolvedValueOnce({ data: { isRead: true } })

    const canOpen = await feedApi.markAsReadBeforeOpen(item)

    expect(canOpen).toBe(true)
    expect(mockFetch).toHaveBeenLastCalledWith('/api/v1/teams/my-team/announcements/7/read', {
      method: 'POST',
    })
    expect(feedApi.feed.value).toHaveLength(1)
    expect(feedApi.feed.value[0]!.isRead).toBe(true)
    expect(mockNotifyWarn).not.toHaveBeenCalled()
  })

  it('ANN-2495-002: 既読済みなら API を叩かずに true', async () => {
    const item = buildItem({ id: 8, isRead: true })
    const feedApi = await setupFeed([item], 0)
    const callsBefore = mockFetch.mock.calls.length

    const canOpen = await feedApi.markAsReadBeforeOpen(item)

    expect(canOpen).toBe(true)
    expect(mockFetch.mock.calls.length).toBe(callsBefore)
  })

  // ──────────────────────────────────────────────
  // ANN-2495-003 / 004: 「もう見えない」＝ ANNOUNCE_001
  // ──────────────────────────────────────────────

  it('ANN-2495-003: ANNOUNCE_001 なら false を返し、一覧から取り除いてトーストで知らせる', async () => {
    const gone = buildItem({ id: 10, isRead: false })
    const alive = buildItem({ id: 11, isRead: false })
    const feedApi = await setupFeed([gone, alive], 2)
    mockFetch.mockRejectedValueOnce(apiError('ANNOUNCE_001'))

    const canOpen = await feedApi.markAsReadBeforeOpen(gone)

    // 遷移しない（削除済みコンテンツの詳細へ飛ばさない）
    expect(canOpen).toBe(false)
    // 一覧から消える（利用者に再読み込み等の再操作を求めない）
    expect(feedApi.feed.value.map(i => i.id)).toEqual([11])
    // 黙って消さず、必ず知らせる
    expect(mockNotifyWarn).toHaveBeenCalledTimes(1)
    expect(tMock).toHaveBeenCalledWith('announcement.no_longer_available')
    // 「もう見えない」は汎用エラートーストに落とさない（二重表示させない）
    expect(mockHandleApiError).not.toHaveBeenCalled()
  })

  it('ANN-2495-004: ANNOUNCE_001 で取り除いた際、未読カウントも 1 減る', async () => {
    const gone = buildItem({ id: 10, isRead: false })
    const alive = buildItem({ id: 11, isRead: false })
    const feedApi = await setupFeed([gone, alive], 2)
    mockFetch.mockRejectedValueOnce(apiError('ANNOUNCE_001'))

    await feedApi.markAsReadBeforeOpen(gone)

    expect(feedApi.meta.value?.unreadCount).toBe(1)
  })

  // ──────────────────────────────────────────────
  // ANN-2495-005 / 006: 「通信に失敗した」は区別する
  // ──────────────────────────────────────────────

  it('ANN-2495-005: 通信失敗では項目を消さず、true を返して遷移は続行する', async () => {
    const item = buildItem({ id: 12, isRead: false })
    const feedApi = await setupFeed([item], 1)
    // ネットワーク断（レスポンス body が無い＝ data.error.code を持たない）
    mockFetch.mockRejectedValueOnce(new Error('Failed to fetch'))

    const canOpen = await feedApi.markAsReadBeforeOpen(item)

    // 既読マークは副作用にすぎず、その失敗で「開く」意図まで巻き添えにしない
    expect(canOpen).toBe(true)
    // 消すと利用者にはデータが消えたように見えるため、必ず残す
    expect(feedApi.feed.value.map(i => i.id)).toEqual([12])
    expect(feedApi.meta.value?.unreadCount).toBe(1)
    expect(mockNotifyWarn).not.toHaveBeenCalled()
  })

  it('ANN-2495-006: 通信失敗ではエラーを握りつぶさず handleApiError に渡す', async () => {
    const item = buildItem({ id: 13, isRead: false })
    const feedApi = await setupFeed([item], 1)
    const networkError = new Error('Failed to fetch')
    mockFetch.mockRejectedValueOnce(networkError)

    await feedApi.markAsReadBeforeOpen(item)

    expect(mockHandleApiError).toHaveBeenCalledTimes(1)
    // 元のエラーを差し替えず、そのまま渡す（PR #2501 と同型の欠陥を作らない）
    expect(mockHandleApiError.mock.calls[0]![0]).toBe(networkError)
  })
})

describe('isAnnouncementGoneError', () => {
  it('ANN-2495-007: ANNOUNCE_001 のみ true', () => {
    expect(isAnnouncementGoneError(apiError('ANNOUNCE_001'))).toBe(true)
    // 別コード・素の Error・null は「通信失敗側」に倒す
    expect(isAnnouncementGoneError(apiError('ANNOUNCE_002'))).toBe(false)
    expect(isAnnouncementGoneError(new Error('Failed to fetch'))).toBe(false)
    expect(isAnnouncementGoneError(null)).toBe(false)
    expect(isAnnouncementGoneError(undefined)).toBe(false)
  })
})
