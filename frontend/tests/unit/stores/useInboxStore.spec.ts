import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useInboxStore } from '~/stores/useInboxStore'
import type { InboxItem, InboxLabel, InboxListResponse, InboxTriageResponse } from '~/types/inbox'

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
  // Phase 2
  getLabels: vi.fn(),
  createLabel: vi.fn(),
  updateLabel: vi.fn(),
  deleteLabel: vi.fn(),
  assignLabel: vi.fn(),
  unassignLabel: vi.fn(),
  bulkAction: vi.fn(),
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

function makeLabel(overrides: Partial<InboxLabel> = {}): InboxLabel {
  return {
    id: 'label-1',
    name: 'テストラベル',
    color: '#6366f1',
    icon: null,
    sortOrder: 0,
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

  // ──────────────────────────────────────────────
  // Phase 2: setLabelFilter
  // ──────────────────────────────────────────────

  describe('setLabelFilter', () => {
    it('ラベルフィルタをセットして fetchInbox が呼ばれる', async () => {
      apiMock.getInbox.mockResolvedValue(makeListResponse([]))
      const store = useInboxStore()

      await store.setLabelFilter('label-1')

      expect(store.labelFilter).toBe('label-1')
      expect(apiMock.getInbox).toHaveBeenCalledTimes(1)
    })

    it('null に設定するとフィルタが解除される', async () => {
      apiMock.getInbox.mockResolvedValue(makeListResponse([]))
      const store = useInboxStore()
      store.labelFilter = 'label-1'

      await store.setLabelFilter(null)

      expect(store.labelFilter).toBeNull()
    })
  })

  // ──────────────────────────────────────────────
  // Phase 2: ラベル CRUD
  // ──────────────────────────────────────────────

  describe('fetchLabels', () => {
    it('正常系: ラベル一覧がストアに格納される', async () => {
      const labels = [makeLabel(), makeLabel({ id: 'label-2', name: 'ラベル2' })]
      apiMock.getLabels.mockResolvedValueOnce({ data: labels })
      const store = useInboxStore()

      await store.fetchLabels()

      expect(store.labels).toHaveLength(2)
      expect(store.labelsLoading).toBe(false)
    })

    it('異常系: API 失敗で error が設定される', async () => {
      apiMock.getLabels.mockRejectedValueOnce({ status: 500 })
      const store = useInboxStore()

      await store.fetchLabels()

      expect(store.labels).toHaveLength(0)
      expect(store.error).toBe('common.error.unknown')
    })
  })

  describe('createLabel', () => {
    it('正常系: ラベルが labels に追加される', async () => {
      const label = makeLabel()
      apiMock.createLabel.mockResolvedValueOnce({ data: label })
      const store = useInboxStore()

      const result = await store.createLabel({ name: 'テストラベル', color: '#6366f1' })

      expect(result).toEqual(label)
      expect(store.labels).toHaveLength(1)
      expect(store.labels[0]).toEqual(label)
    })

    it('異常系: API 失敗で null を返す', async () => {
      apiMock.createLabel.mockRejectedValueOnce({ status: 422 })
      const store = useInboxStore()

      const result = await store.createLabel({ name: 'テスト' })

      expect(result).toBeNull()
    })
  })

  describe('deleteLabel', () => {
    it('正常系: 楽観削除後 API 成功で labels から除去される', async () => {
      const label = makeLabel()
      apiMock.deleteLabel.mockResolvedValueOnce(undefined)
      const store = useInboxStore()
      store.labels = [label]

      const ok = await store.deleteLabel(label.id)

      expect(ok).toBe(true)
      expect(store.labels).toHaveLength(0)
    })

    it('異常系: API 失敗で labels がロールバックされる', async () => {
      const label = makeLabel()
      apiMock.deleteLabel.mockRejectedValueOnce({ status: 500 })
      const store = useInboxStore()
      store.labels = [label]

      const ok = await store.deleteLabel(label.id)

      expect(ok).toBe(false)
      // ロールバックで元に戻る
      expect(store.labels).toHaveLength(1)
      expect(store.labels[0]?.id).toBe(label.id)
    })
  })

  // ──────────────────────────────────────────────
  // Phase 2: ラベル付与/解除（楽観更新）
  // ──────────────────────────────────────────────

  describe('assignLabel', () => {
    it('正常系: item.labels に楽観追加される', async () => {
      const item = makeItem()
      const label = makeLabel()
      apiMock.getInbox.mockResolvedValueOnce(makeListResponse([item]))
      apiMock.assignLabel.mockResolvedValueOnce(label)
      const store = useInboxStore()
      await store.fetchInbox()
      store.labels = [label]

      const ok = await store.assignLabel('NOTIFICATION', 1, label.id)

      expect(ok).toBe(true)
      expect(store.items[0]?.labels).toHaveLength(1)
      expect(store.items[0]?.labels[0]?.id).toBe(label.id)
    })

    it('異常系: API 失敗で item.labels がロールバックされる', async () => {
      const item = makeItem()
      const label = makeLabel()
      apiMock.getInbox.mockResolvedValueOnce(makeListResponse([item]))
      apiMock.assignLabel.mockRejectedValueOnce({ status: 422 })
      const store = useInboxStore()
      await store.fetchInbox()
      store.labels = [label]

      const ok = await store.assignLabel('NOTIFICATION', 1, label.id)

      expect(ok).toBe(false)
      // ロールバックで空に戻る
      expect(store.items[0]?.labels).toHaveLength(0)
    })
  })

  describe('unassignLabel', () => {
    it('正常系: item.labels からラベルが楽観削除される', async () => {
      const label = makeLabel()
      const item = makeItem({ labels: [label] })
      apiMock.getInbox.mockResolvedValueOnce(makeListResponse([item]))
      apiMock.unassignLabel.mockResolvedValueOnce(undefined)
      const store = useInboxStore()
      await store.fetchInbox()

      const ok = await store.unassignLabel('NOTIFICATION', 1, label.id)

      expect(ok).toBe(true)
      expect(store.items[0]?.labels).toHaveLength(0)
    })

    it('異常系: API 失敗で item.labels がロールバックされる', async () => {
      const label = makeLabel()
      const item = makeItem({ labels: [label] })
      apiMock.getInbox.mockResolvedValueOnce(makeListResponse([item]))
      apiMock.unassignLabel.mockRejectedValueOnce({ status: 404 })
      const store = useInboxStore()
      await store.fetchInbox()

      const ok = await store.unassignLabel('NOTIFICATION', 1, label.id)

      expect(ok).toBe(false)
      // ロールバックでラベルが元に戻る
      expect(store.items[0]?.labels).toHaveLength(1)
    })
  })

  // ──────────────────────────────────────────────
  // Phase 2: bulk 選択モード
  // ──────────────────────────────────────────────

  describe('toggleSelectionMode', () => {
    it('ON/OFF が切り替わる', () => {
      const store = useInboxStore()
      expect(store.selectionMode).toBe(false)

      store.toggleSelectionMode()
      expect(store.selectionMode).toBe(true)

      store.toggleSelectionMode()
      expect(store.selectionMode).toBe(false)
    })

    it('モード終了時に selectedKeys がクリアされる', () => {
      const store = useInboxStore()
      store.selectionMode = true
      store.selectedKeys = new Set(['NOTIFICATION:1'])

      store.toggleSelectionMode()

      expect(store.selectedKeys.size).toBe(0)
    })
  })

  describe('toggleSelect', () => {
    it('未選択キーを追加できる', () => {
      const store = useInboxStore()
      store.toggleSelect('NOTIFICATION:1')
      expect(store.selectedKeys.has('NOTIFICATION:1')).toBe(true)
    })

    it('選択済みキーを削除できる', () => {
      const store = useInboxStore()
      store.selectedKeys = new Set(['NOTIFICATION:1'])
      store.toggleSelect('NOTIFICATION:1')
      expect(store.selectedKeys.has('NOTIFICATION:1')).toBe(false)
    })
  })

  describe('runBulk', () => {
    it('正常系: bulk API を呼び結果を返す', async () => {
      apiMock.getInbox.mockResolvedValue(makeListResponse([]))
      apiMock.bulkAction.mockResolvedValueOnce({ data: { processed: 2, skipped: 0 } })
      const store = useInboxStore()
      store.selectedKeys = new Set(['NOTIFICATION:1', 'NOTIFICATION:2'])

      const result = await store.runBulk('ARCHIVE')

      expect(result).toEqual({ processed: 2, skipped: 0 })
      expect(store.selectedKeys.size).toBe(0)
      expect(apiMock.bulkAction).toHaveBeenCalledTimes(1)
    })

    it('選択なしのとき null を返し API を呼ばない', async () => {
      const store = useInboxStore()

      const result = await store.runBulk('ARCHIVE')

      expect(result).toBeNull()
      expect(apiMock.bulkAction).not.toHaveBeenCalled()
    })

    it('異常系: API 失敗で null を返す', async () => {
      apiMock.bulkAction.mockRejectedValueOnce({ status: 500 })
      const store = useInboxStore()
      store.selectedKeys = new Set(['NOTIFICATION:1'])

      const result = await store.runBulk('ARCHIVE')

      expect(result).toBeNull()
      expect(store.error).toBe('common.error.unknown')
    })
  })
})
