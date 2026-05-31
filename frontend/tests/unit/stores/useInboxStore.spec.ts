import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useInboxStore } from '~/stores/useInboxStore'
import type { InboxItem, InboxListResponse, InboxTriageResponse } from '~/types/inbox'

/**
 * F04.11 useInboxStore のユニットテスト。
 *
 * - 楽観更新＋ロールバック（snooze / archive 成功で state 反映、API 失敗でロールバック）
 * - fetchInbox / fetchMore のページング
 * - _handleError で error 設定
 */

// === Mock: useInboxApi ===
const apiMock = {
  getInbox: vi.fn(),
  getSummary: vi.fn(),
  snooze: vi.fn(),
  unsnooze: vi.fn(),
  archive: vi.fn(),
  unarchive: vi.fn(),
}

vi.mock('~/composables/useInboxApi', () => ({
  useInboxApi: () => apiMock,
  priorityI18nKey: (p: string) => `inbox.priority.${p.toLowerCase()}`,
  prioritySeverity: (p: string) => p === 'URGENT' ? 'danger' : 'warn',
  sourceTypeI18nKey: (s: string) => `inbox.source.${s.toLowerCase()}`,
  sourceTypeIcon: (s: string) => `pi pi-${s.toLowerCase()}`,
}))

// useAuthStore 最小モック（computeSnoozeUntil が呼ぶ）
vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 1, timezone: 'Asia/Tokyo' } }),
}))


function makeItem(overrides: Partial<InboxItem> = {}): InboxItem {
  return {
    id: 'NOTIFICATION:1',
    sourceType: 'NOTIFICATION',
    sourceId: 1,
    title: 'テスト通知',
    excerpt: null,
    priority: 'NORMAL',
    scope: null,
    actionUrl: null,
    occurredAt: '2026-05-31T09:00:00Z',
    state: 'UNREAD',
    snoozedUntil: null,
    labels: [],
    ...overrides,
  }
}

function makeListResponse(items: InboxItem[], hasMore = false): InboxListResponse {
  return {
    data: {
      items,
      page: 0,
      size: 20,
      totalEstimated: items.length,
      hasMore,
    },
  }
}

function makeTriageResponse(item: InboxItem): InboxTriageResponse {
  return { data: item }
}

describe('useInboxStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    for (const fn of Object.values(apiMock)) {
      fn.mockReset()
    }
  })

  // ──────────────────────────────────────────────
  // fetchInbox
  // ──────────────────────────────────────────────

  describe('fetchInbox', () => {
    it('正常系: items / hasMore / page が反映される', async () => {
      const items = [makeItem(), makeItem({ id: 'NOTIFICATION:2', sourceId: 2, title: '通知2' })]
      apiMock.getInbox.mockResolvedValueOnce(makeListResponse(items, true))
      const store = useInboxStore()

      await store.fetchInbox()

      expect(store.items).toHaveLength(2)
      expect(store.hasMore).toBe(true)
      expect(store.loading).toBe(false)
      expect(store.error).toBeNull()
    })

    it('異常系: API 失敗で error が設定される', async () => {
      apiMock.getInbox.mockRejectedValueOnce({ status: 500 })
      const store = useInboxStore()

      await store.fetchInbox()

      expect(store.items).toHaveLength(0)
      expect(store.error).toBe('common.error.unknown')
      expect(store.loading).toBe(false)
    })

    it('401 エラーで unauthorized キーが設定される', async () => {
      apiMock.getInbox.mockRejectedValueOnce({ status: 401 })
      const store = useInboxStore()

      await store.fetchInbox()

      expect(store.error).toBe('common.error.unauthorized')
    })

    it('404 エラーで notFound キーが設定される', async () => {
      apiMock.getInbox.mockRejectedValueOnce({ response: { status: 404 } })
      const store = useInboxStore()

      await store.fetchInbox()

      expect(store.error).toBe('common.error.notFound')
    })
  })

  // ──────────────────────────────────────────────
  // fetchMore (ページング)
  // ──────────────────────────────────────────────

  describe('fetchMore', () => {
    it('正常系: 次ページのアイテムが items に追加される', async () => {
      const page0Items = [makeItem()]
      const page1Items = [makeItem({ id: 'NOTIFICATION:2', sourceId: 2, title: '2ページ目' })]
      apiMock.getInbox.mockResolvedValueOnce({
        data: { items: page0Items, page: 0, size: 20, totalEstimated: 2, hasMore: true },
      })
      const store = useInboxStore()
      await store.fetchInbox()

      apiMock.getInbox.mockResolvedValueOnce({
        data: { items: page1Items, page: 1, size: 20, totalEstimated: 2, hasMore: false },
      })
      await store.fetchMore()

      expect(store.items).toHaveLength(2)
      expect(store.page).toBe(1)
      expect(store.hasMore).toBe(false)
    })

    it('hasMore = false のとき fetchMore は何もしない', async () => {
      const store = useInboxStore()
      store.hasMore = false
      await store.fetchMore()
      expect(apiMock.getInbox).not.toHaveBeenCalled()
    })

    it('loading = true のとき fetchMore は何もしない', async () => {
      const store = useInboxStore()
      store.hasMore = true
      store.loading = true
      await store.fetchMore()
      expect(apiMock.getInbox).not.toHaveBeenCalled()
    })
  })

  // ──────────────────────────────────────────────
  // snooze: 楽観更新 + ロールバック
  // ──────────────────────────────────────────────

  describe('snooze', () => {
    it('正常系: 即 state が SNOOZED に更新され、BE 返却値で確定する', async () => {
      const original = makeItem({ state: 'UNREAD' })
      const snoozed = makeItem({ state: 'SNOOZED', snoozedUntil: '2026-06-01T12:00:00Z' })
      apiMock.getInbox.mockResolvedValueOnce(makeListResponse([original]))
      const store = useInboxStore()
      await store.fetchInbox()

      apiMock.snooze.mockResolvedValueOnce(makeTriageResponse(snoozed))
      const ok = await store.snooze('NOTIFICATION', 1, '2026-06-01T12:00:00Z')

      expect(ok).toBe(true)
      expect(store.items[0]?.state).toBe('SNOOZED')
      expect(store.items[0]?.snoozedUntil).toBe('2026-06-01T12:00:00Z')
    })

    it('異常系: API 失敗で previous にロールバックされる', async () => {
      const original = makeItem({ state: 'UNREAD' })
      apiMock.getInbox.mockResolvedValueOnce(makeListResponse([original]))
      const store = useInboxStore()
      await store.fetchInbox()

      apiMock.snooze.mockRejectedValueOnce(new Error('API error'))
      const ok = await store.snooze('NOTIFICATION', 1, '2026-06-01T12:00:00Z')

      expect(ok).toBe(false)
      // ロールバックで元の UNREAD に戻る
      expect(store.items[0]?.state).toBe('UNREAD')
      expect(store.error).toBe('common.error.unknown')
    })
  })

  // ──────────────────────────────────────────────
  // archive: 楽観更新 + ロールバック
  // ──────────────────────────────────────────────

  describe('archive', () => {
    it('正常系: state が ARCHIVED に更新される', async () => {
      const original = makeItem({ state: 'UNREAD' })
      const archived = makeItem({ state: 'ARCHIVED' })
      apiMock.getInbox.mockResolvedValueOnce(makeListResponse([original]))
      const store = useInboxStore()
      await store.fetchInbox()

      apiMock.archive.mockResolvedValueOnce(makeTriageResponse(archived))
      const ok = await store.archive('NOTIFICATION', 1)

      expect(ok).toBe(true)
      expect(store.items[0]?.state).toBe('ARCHIVED')
    })

    it('異常系: API 失敗で previous にロールバックされる', async () => {
      const original = makeItem({ state: 'UNREAD' })
      apiMock.getInbox.mockResolvedValueOnce(makeListResponse([original]))
      const store = useInboxStore()
      await store.fetchInbox()

      apiMock.archive.mockRejectedValueOnce(new Error('API error'))
      const ok = await store.archive('NOTIFICATION', 1)

      expect(ok).toBe(false)
      expect(store.items[0]?.state).toBe('UNREAD')
    })
  })

  // ──────────────────────────────────────────────
  // unsnooze: 楽観更新 + ロールバック
  // ──────────────────────────────────────────────

  describe('unsnooze', () => {
    it('正常系: state が UNREAD に戻る', async () => {
      const original = makeItem({ state: 'SNOOZED', snoozedUntil: '2026-06-01T12:00:00Z' })
      const unsnoozed = makeItem({ state: 'UNREAD', snoozedUntil: null })
      apiMock.getInbox.mockResolvedValueOnce(makeListResponse([original]))
      const store = useInboxStore()
      await store.fetchInbox()

      apiMock.unsnooze.mockResolvedValueOnce(makeTriageResponse(unsnoozed))
      const ok = await store.unsnooze('NOTIFICATION', 1)

      expect(ok).toBe(true)
      expect(store.items[0]?.state).toBe('UNREAD')
      expect(store.items[0]?.snoozedUntil).toBeNull()
    })

    it('異常系: API 失敗で previous にロールバックされる', async () => {
      const original = makeItem({ state: 'SNOOZED', snoozedUntil: '2026-06-01T12:00:00Z' })
      apiMock.getInbox.mockResolvedValueOnce(makeListResponse([original]))
      const store = useInboxStore()
      await store.fetchInbox()

      apiMock.unsnooze.mockRejectedValueOnce(new Error('API error'))
      const ok = await store.unsnooze('NOTIFICATION', 1)

      expect(ok).toBe(false)
      expect(store.items[0]?.state).toBe('SNOOZED')
    })
  })

  // ──────────────────────────────────────────────
  // getters
  // ──────────────────────────────────────────────

  describe('getters', () => {
    it('inboxCount / snoozedCount / archivedCount がサマリを返す', async () => {
      apiMock.getSummary.mockResolvedValueOnce({
        data: {
          byState: { INBOX: 5, SNOOZED: 2, ARCHIVED: 10 },
          byPriority: {},
          bySourceType: {},
        },
      })
      const store = useInboxStore()
      await store.fetchSummary()

      expect(store.inboxCount).toBe(5)
      expect(store.snoozedCount).toBe(2)
      expect(store.archivedCount).toBe(10)
    })

    it('サマリ未取得時は 0 を返す', () => {
      const store = useInboxStore()
      expect(store.inboxCount).toBe(0)
      expect(store.snoozedCount).toBe(0)
      expect(store.archivedCount).toBe(0)
    })
  })

  // ──────────────────────────────────────────────
  // switchTab
  // ──────────────────────────────────────────────

  describe('switchTab', () => {
    it('タブが切り替わり fetchInbox が呼ばれる', async () => {
      apiMock.getInbox.mockResolvedValue(makeListResponse([]))
      const store = useInboxStore()

      await store.switchTab('SNOOZED')

      expect(store.currentTab).toBe('SNOOZED')
      expect(apiMock.getInbox).toHaveBeenCalledTimes(1)
    })

    it('同じタブへの切替は何もしない', async () => {
      const store = useInboxStore()
      store.currentTab = 'INBOX'
      await store.switchTab('INBOX')
      expect(apiMock.getInbox).not.toHaveBeenCalled()
    })
  })
})
