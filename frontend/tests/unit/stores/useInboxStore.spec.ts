import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useInboxStore } from '~/stores/useInboxStore'
import type { InboxItem, InboxItemRef, InboxLabel, InboxListResponse, InboxTriageResponse, SuggestedLabel } from '~/types/inbox'

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
  // Phase 3 (wave3b)
  suggestApply: vi.fn(),
}

vi.mock('~/composables/useInboxApi', () => ({
  useInboxApi: () => apiMock,
  priorityI18nKey: (p: string) => `inbox.priority.${p.toLowerCase()}`,
  prioritySeverity: (p: string) => p === 'URGENT' ? 'danger' : 'warn',
  sourceTypeI18nKey: (s: string) => `inbox.source.${s.toLowerCase()}`,
  sourceTypeIcon: (s: string) => `pi pi-${s.toLowerCase()}`,
  suggestionKeyI18nKey: (k: string) => `inbox.suggestion.${k.replace(/_([a-z])/gi, (_, c: string) => c.toUpperCase()).toLowerCase()}`,
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

/** Phase 3: groupCount > 1 のグループカードを生成するヘルパー。 */
function makeGroupItem(overrides: Partial<InboxItem> = {}): InboxItem {
  const groupMembers: InboxItemRef[] = [
    { sourceType: 'NOTIFICATION', sourceId: 10 },
    { sourceType: 'ANNOUNCEMENT', sourceId: 20 },
  ]
  return makeItem({
    id: 'NOTIFICATION:10',
    sourceType: 'NOTIFICATION',
    sourceId: 10,
    groupCount: 2,
    groupMembers,
    canonicalRef: 'BLOG_POST:5',
    ...overrides,
  })
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

    it('異常系: API 失敗でエラーが throw される', async () => {
      const err = { status: 422 }
      apiMock.createLabel.mockRejectedValueOnce(err)
      const store = useInboxStore()

      await expect(store.createLabel({ name: 'テスト' })).rejects.toMatchObject({ status: 422 })
      // エラー時はラベルリストに追加されない
      expect(store.labels).toHaveLength(0)
    })
  })

  describe('deleteLabel', () => {
    it('正常系: 楽観削除後 API 成功で labels から除去される', async () => {
      const label = makeLabel()
      apiMock.deleteLabel.mockResolvedValueOnce(undefined)
      const store = useInboxStore()
      store.labels = [label]

      await store.deleteLabel(label.id)

      expect(store.labels).toHaveLength(0)
    })

    it('異常系: API 失敗でエラーが throw され labels がロールバックされる', async () => {
      const label = makeLabel()
      apiMock.deleteLabel.mockRejectedValueOnce({ status: 500 })
      const store = useInboxStore()
      store.labels = [label]

      await expect(store.deleteLabel(label.id)).rejects.toMatchObject({ status: 500 })
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

  // ──────────────────────────────────────────────
  // Phase 3: グループカード triage（名寄せ bulk 伝播）
  // ──────────────────────────────────────────────

  describe('Phase 3: groupCount > 1 の triage — bulk 伝播', () => {
    describe('INBOX-STORE-GRP-001: groupCount > 1 の snooze が bulkAction(SNOOZE) を呼ぶ', () => {
      it('bulkAction に action=SNOOZE と groupMembers が渡る', async () => {
        const groupItem = makeGroupItem()
        const store = useInboxStore()
        store.items = [groupItem]

        apiMock.bulkAction.mockResolvedValueOnce({ data: { processed: 2, skipped: 0 } })

        const ok = await store.snooze('NOTIFICATION', 10, '2026-06-01T12:00:00Z')

        expect(ok).toBe(true)
        expect(apiMock.bulkAction).toHaveBeenCalledWith({
          action: 'SNOOZE',
          items: [
            { sourceType: 'NOTIFICATION', sourceId: 10 },
            { sourceType: 'ANNOUNCEMENT', sourceId: 20 },
          ],
          snoozedUntil: '2026-06-01T12:00:00Z',
        })
        // 単一 triage API は呼ばれない
        expect(apiMock.snooze).not.toHaveBeenCalled()
      })
    })

    describe('INBOX-STORE-GRP-002: groupCount > 1 の archive が bulkAction(ARCHIVE) を呼ぶ', () => {
      it('bulkAction に action=ARCHIVE と groupMembers が渡る', async () => {
        const groupItem = makeGroupItem()
        const store = useInboxStore()
        store.items = [groupItem]

        apiMock.bulkAction.mockResolvedValueOnce({ data: { processed: 2, skipped: 0 } })

        const ok = await store.archive('NOTIFICATION', 10)

        expect(ok).toBe(true)
        expect(apiMock.bulkAction).toHaveBeenCalledWith({
          action: 'ARCHIVE',
          items: [
            { sourceType: 'NOTIFICATION', sourceId: 10 },
            { sourceType: 'ANNOUNCEMENT', sourceId: 20 },
          ],
        })
        expect(apiMock.archive).not.toHaveBeenCalled()
      })
    })

    describe('INBOX-STORE-GRP-003: groupCount > 1 の unarchive が bulkAction(UNARCHIVE) を呼ぶ', () => {
      it('bulkAction に action=UNARCHIVE と groupMembers が渡る', async () => {
        const groupItem = makeGroupItem({ state: 'ARCHIVED' })
        const store = useInboxStore()
        store.items = [groupItem]

        apiMock.bulkAction.mockResolvedValueOnce({ data: { processed: 2, skipped: 0 } })

        const ok = await store.unarchive('NOTIFICATION', 10)

        expect(ok).toBe(true)
        expect(apiMock.bulkAction).toHaveBeenCalledWith({
          action: 'UNARCHIVE',
          items: [
            { sourceType: 'NOTIFICATION', sourceId: 10 },
            { sourceType: 'ANNOUNCEMENT', sourceId: 20 },
          ],
        })
        expect(apiMock.unarchive).not.toHaveBeenCalled()
      })
    })

    describe('INBOX-STORE-GRP-004: グループカード楽観除去', () => {
      it('archive 成功後に代表カードが items から消える', async () => {
        const groupItem = makeGroupItem()
        const otherItem = makeItem({ id: 'MENTION:99', sourceType: 'MENTION', sourceId: 99 })
        const store = useInboxStore()
        store.items = [groupItem, otherItem]

        apiMock.bulkAction.mockResolvedValueOnce({ data: { processed: 2, skipped: 0 } })

        await store.archive('NOTIFICATION', 10)

        expect(store.items.find((i) => i.id === 'NOTIFICATION:10')).toBeUndefined()
        expect(store.items.find((i) => i.id === 'MENTION:99')).toBeDefined()
      })

      it('snooze 成功後に代表カードが items から消える', async () => {
        const groupItem = makeGroupItem()
        const store = useInboxStore()
        store.items = [groupItem]

        apiMock.bulkAction.mockResolvedValueOnce({ data: { processed: 2, skipped: 0 } })

        await store.snooze('NOTIFICATION', 10, '2026-06-01T12:00:00Z')

        expect(store.items.find((i) => i.id === 'NOTIFICATION:10')).toBeUndefined()
      })
    })

    describe('INBOX-STORE-GRP-005: API 失敗時ロールバック', () => {
      it('archive bulkAction 失敗時に items が元に戻る', async () => {
        const groupItem = makeGroupItem()
        const store = useInboxStore()
        store.items = [groupItem]

        apiMock.bulkAction.mockRejectedValueOnce(new Error('Network Error'))

        const ok = await store.archive('NOTIFICATION', 10)

        expect(ok).toBe(false)
        expect(store.items.find((i) => i.id === 'NOTIFICATION:10')).toBeDefined()
      })

      it('snooze bulkAction 失敗時に items が元に戻る', async () => {
        const groupItem = makeGroupItem()
        const store = useInboxStore()
        store.items = [groupItem]

        apiMock.bulkAction.mockRejectedValueOnce(new Error('Network Error'))

        const ok = await store.snooze('NOTIFICATION', 10, '2026-06-01T12:00:00Z')

        expect(ok).toBe(false)
        expect(store.items.find((i) => i.id === 'NOTIFICATION:10')).toBeDefined()
      })
    })

    // ──────────────────────────────────────────────
    // Phase 3: グループ unsnooze の挙動（GRP-008）
    // ──────────────────────────────────────────────

    describe('INBOX-STORE-GRP-008: groupCount > 1 の unsnooze', () => {
      it('正常系: groupMembers 件数分 api.unsnooze が呼ばれ、その後 fetchInbox で確定する', async () => {
        const groupItem = makeGroupItem({ state: 'SNOOZED', snoozedUntil: '2026-06-01T12:00:00Z' })
        const store = useInboxStore()
        store.items = [groupItem]

        // 全メンバーの unsnooze が成功
        apiMock.unsnooze.mockResolvedValue({ data: makeItem({ state: 'UNREAD' }) })
        // fetchInbox（確定用）
        apiMock.getInbox.mockResolvedValueOnce(makeListResponse([]))

        const ok = await store.unsnooze('NOTIFICATION', 10)

        expect(ok).toBe(true)
        // groupMembers の件数（2件）分 api.unsnooze が呼ばれる
        expect(apiMock.unsnooze).toHaveBeenCalledTimes(2)
        expect(apiMock.unsnooze).toHaveBeenCalledWith('NOTIFICATION', 10)
        expect(apiMock.unsnooze).toHaveBeenCalledWith('ANNOUNCEMENT', 20)
        // その後 getInbox（fetchInbox）が走る
        expect(apiMock.getInbox).toHaveBeenCalledTimes(1)
      })

      it('失敗系（全件失敗）: previous にロールバックし fetchInbox で再同期される', async () => {
        const groupItem = makeGroupItem({ state: 'SNOOZED', snoozedUntil: '2026-06-01T12:00:00Z' })
        const otherItem = makeItem({ id: 'MENTION:99', sourceType: 'MENTION', sourceId: 99 })
        const store = useInboxStore()
        store.items = [groupItem, otherItem]

        // 全メンバーの unsnooze が失敗
        apiMock.unsnooze.mockRejectedValue(new Error('Network Error'))
        // fetchInbox（再同期用）— 実サーバ側の状態（snoozed のまま）を返す想定
        apiMock.getInbox.mockResolvedValueOnce(makeListResponse([groupItem, otherItem]))

        const ok = await store.unsnooze('NOTIFICATION', 10)

        expect(ok).toBe(false)
        // ロールバック後に fetchInbox が走る（誤表示防止・再同期）
        expect(apiMock.getInbox).toHaveBeenCalledTimes(1)
        // fetchInbox 後の items がサーバ返却値で上書きされている
        expect(store.items).toHaveLength(2)
      })

      it('部分失敗系: 一部成功・一部失敗でも fetchInbox で再同期される', async () => {
        const groupItem = makeGroupItem({ state: 'SNOOZED', snoozedUntil: '2026-06-01T12:00:00Z' })
        const store = useInboxStore()
        store.items = [groupItem]

        // 1件目成功、2件目失敗（部分失敗）
        apiMock.unsnooze
          .mockResolvedValueOnce({ data: makeItem({ state: 'UNREAD' }) })
          .mockRejectedValueOnce(new Error('Network Error'))
        // fetchInbox（再同期用）
        apiMock.getInbox.mockResolvedValueOnce(makeListResponse([groupItem]))

        const ok = await store.unsnooze('NOTIFICATION', 10)

        // 部分成功は成功扱い（allFailed = false）
        expect(ok).toBe(true)
        // 必ず fetchInbox で再同期される
        expect(apiMock.getInbox).toHaveBeenCalledTimes(1)
      })
    })

    describe('INBOX-STORE-GRP-006: groupCount <= 1 は単一 triage（回帰）', () => {
      it('snooze: groupCount=1 のとき単一 triage API を呼ぶ', async () => {
        const singleItem = makeItem({ groupCount: 1 })
        apiMock.getInbox.mockResolvedValueOnce(makeListResponse([singleItem]))
        const store = useInboxStore()
        await store.fetchInbox()

        apiMock.snooze.mockResolvedValueOnce(
          makeTriageResponse({ ...singleItem, state: 'SNOOZED', snoozedUntil: '2026-06-01T12:00:00Z' }),
        )

        await store.snooze('NOTIFICATION', 1, '2026-06-01T12:00:00Z')

        expect(apiMock.snooze).toHaveBeenCalledWith('NOTIFICATION', 1, '2026-06-01T12:00:00Z')
        expect(apiMock.bulkAction).not.toHaveBeenCalled()
      })

      it('archive: groupCount=1 のとき単一 triage API を呼ぶ', async () => {
        const singleItem = makeItem({ groupCount: 1 })
        apiMock.getInbox.mockResolvedValueOnce(makeListResponse([singleItem]))
        const store = useInboxStore()
        await store.fetchInbox()

        apiMock.archive.mockResolvedValueOnce(makeTriageResponse({ ...singleItem, state: 'ARCHIVED' }))

        await store.archive('NOTIFICATION', 1)

        expect(apiMock.archive).toHaveBeenCalledWith('NOTIFICATION', 1)
        expect(apiMock.bulkAction).not.toHaveBeenCalled()
      })
    })

    describe('INBOX-STORE-GRP-007: groupCount 未定義（旧BE互換）は単一 triage（回帰）', () => {
      it('snooze: groupCount フィールドなし → 単一 triage API を呼ぶ', async () => {
        // groupCount フィールドなし（旧 BE）
        const legacyItem = makeItem()
        apiMock.getInbox.mockResolvedValueOnce(makeListResponse([legacyItem]))
        const store = useInboxStore()
        await store.fetchInbox()

        apiMock.snooze.mockResolvedValueOnce(
          makeTriageResponse({ ...legacyItem, state: 'SNOOZED', snoozedUntil: '2026-06-01T12:00:00Z' }),
        )

        await store.snooze('NOTIFICATION', 1, '2026-06-01T12:00:00Z')

        expect(apiMock.snooze).toHaveBeenCalledTimes(1)
        expect(apiMock.bulkAction).not.toHaveBeenCalled()
      })

      it('archive: groupCount フィールドなし → 単一 triage API を呼ぶ', async () => {
        const legacyItem = makeItem()
        apiMock.getInbox.mockResolvedValueOnce(makeListResponse([legacyItem]))
        const store = useInboxStore()
        await store.fetchInbox()

        apiMock.archive.mockResolvedValueOnce(makeTriageResponse({ ...legacyItem, state: 'ARCHIVED' }))

        await store.archive('NOTIFICATION', 1)

        expect(apiMock.archive).toHaveBeenCalledTimes(1)
        expect(apiMock.bulkAction).not.toHaveBeenCalled()
      })
    })
  })

  // ──────────────────────────────────────────────
  // suggestApply (wave3b): 自動ラベリング提案付与 楽観更新 + ロールバック
  // ──────────────────────────────────────────────

  describe('suggestApply', () => {
    /** 提案付きアイテムを生成するヘルパー。 */
    function makeItemWithSuggestion(suggestion: SuggestedLabel): InboxItem {
      return makeItem({ suggestedLabels: [suggestion] })
    }

    const suggestion: SuggestedLabel = {
      suggestionKey: 'REPLY_NEEDED',
      color: '#ef4444',
      existingLabelId: null,
    }

    it('正常系: 楽観的に仮ラベルが追加され、API 成功で id が確定する', async () => {
      const item = makeItemWithSuggestion(suggestion)
      apiMock.getInbox.mockResolvedValueOnce(makeListResponse([item]))
      const store = useInboxStore()
      await store.fetchInbox()

      const returnedLabel: InboxLabel = {
        id: 'label-returned',
        name: '要返信',
        color: '#ef4444',
        icon: null,
        sortOrder: 0,
      }
      apiMock.suggestApply.mockResolvedValueOnce(returnedLabel)

      const ok = await store.suggestApply('NOTIFICATION', 1, suggestion, '要返信')

      expect(ok).toBe(true)
      // ラベルが付与されている
      expect(store.items[0]?.labels).toHaveLength(1)
      expect(store.items[0]?.labels[0]?.id).toBe('label-returned')
      expect(store.items[0]?.labels[0]?.name).toBe('要返信')
      // 提案は除去されている
      expect(store.items[0]?.suggestedLabels).toHaveLength(0)
    })

    it('正常系: API に正しい引数が渡される', async () => {
      const item = makeItemWithSuggestion(suggestion)
      apiMock.getInbox.mockResolvedValueOnce(makeListResponse([item]))
      const store = useInboxStore()
      await store.fetchInbox()

      const returnedLabel: InboxLabel = {
        id: 'label-ok',
        name: '要返信',
        color: '#ef4444',
        icon: null,
        sortOrder: 0,
      }
      apiMock.suggestApply.mockResolvedValueOnce(returnedLabel)

      await store.suggestApply('NOTIFICATION', 1, suggestion, '要返信')

      expect(apiMock.suggestApply).toHaveBeenCalledWith('NOTIFICATION', 1, '要返信', '#ef4444')
    })

    it('正常系: ストアの labels マスターに返却ラベルが追加される（重複なし）', async () => {
      const item = makeItemWithSuggestion(suggestion)
      apiMock.getInbox.mockResolvedValueOnce(makeListResponse([item]))
      const store = useInboxStore()
      await store.fetchInbox()

      const returnedLabel: InboxLabel = {
        id: 'new-label',
        name: '要返信',
        color: '#ef4444',
        icon: null,
        sortOrder: 0,
      }
      apiMock.suggestApply.mockResolvedValueOnce(returnedLabel)

      await store.suggestApply('NOTIFICATION', 1, suggestion, '要返信')

      expect(store.labels.some((l) => l.id === 'new-label')).toBe(true)
    })

    it('正常系: ラベルが既にストアにある場合は重複追加しない', async () => {
      const item = makeItemWithSuggestion(suggestion)
      apiMock.getInbox.mockResolvedValueOnce(makeListResponse([item]))
      const store = useInboxStore()
      await store.fetchInbox()

      const existingLabel: InboxLabel = {
        id: 'existing-label',
        name: '要返信',
        color: '#ef4444',
        icon: null,
        sortOrder: 0,
      }
      // 事前にストアに追加しておく
      store.labels.push(existingLabel)
      apiMock.suggestApply.mockResolvedValueOnce(existingLabel)

      await store.suggestApply('NOTIFICATION', 1, suggestion, '要返信')

      // 重複は追加されない
      expect(store.labels.filter((l) => l.id === 'existing-label')).toHaveLength(1)
    })

    it('異常系: API 失敗でロールバック・error がセットされる', async () => {
      const item = makeItemWithSuggestion(suggestion)
      apiMock.getInbox.mockResolvedValueOnce(makeListResponse([item]))
      const store = useInboxStore()
      await store.fetchInbox()

      apiMock.suggestApply.mockRejectedValueOnce({ status: 500 })

      const ok = await store.suggestApply('NOTIFICATION', 1, suggestion, '要返信')

      expect(ok).toBe(false)
      // ロールバック: 元の状態（ラベルなし・提案あり）に戻る
      expect(store.items[0]?.labels).toHaveLength(0)
      expect(store.items[0]?.suggestedLabels).toHaveLength(1)
      expect(store.items[0]?.suggestedLabels?.[0]?.suggestionKey).toBe('REPLY_NEEDED')
      expect(store.error).toBe('common.error.unknown')
    })

    it('異常系: 上限超過（4xx）でもロールバックされる', async () => {
      const item = makeItemWithSuggestion(suggestion)
      apiMock.getInbox.mockResolvedValueOnce(makeListResponse([item]))
      const store = useInboxStore()
      await store.fetchInbox()

      apiMock.suggestApply.mockRejectedValueOnce({ status: 422 })

      const ok = await store.suggestApply('NOTIFICATION', 1, suggestion, '要返信')

      expect(ok).toBe(false)
      // ロールバックで提案は元に戻る
      expect(store.items[0]?.suggestedLabels).toHaveLength(1)
    })
  })
})
