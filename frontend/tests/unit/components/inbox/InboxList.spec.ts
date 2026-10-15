import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import InboxList from '~/components/inbox/InboxList.vue'
import type { InboxItem, InboxItemRef } from '~/types/inbox'

/**
 * F04.11 InboxList.vue のユニットテスト。
 *
 * - 状態タブの表示・切替
 * - 空状態の表示（data-testid="inbox-empty-state"）
 * - snooze / archive ボタンがストアのアクションを呼ぶ
 */

// ──────────────────────────────────────────────
// モック定義
// ──────────────────────────────────────────────

const storeMock = {
  // state
  items: [] as InboxItem[],
  currentTab: 'INBOX' as string,
  loading: false,
  summaryLoading: false,
  labelsLoading: false,
  hasMore: false,
  totalEstimated: 0,
  page: 0,
  error: null as string | null,
  priorityFilter: [] as string[],
  sourceTypeFilter: [] as string[],
  summaryByState: {} as Record<string, number>,
  summaryByPriority: {} as Record<string, number>,
  summaryBySourceType: {} as Record<string, number>,
  labelFilter: null as string | null,
  labels: [] as InboxItem[],
  selectionMode: false,
  selectedKeys: new Set<string>(),
  // getters
  get inboxCount() {
    return this.summaryByState['INBOX'] ?? 0
  },
  get snoozedCount() {
    return this.summaryByState['SNOOZED'] ?? 0
  },
  get archivedCount() {
    return this.summaryByState['ARCHIVED'] ?? 0
  },
  // actions
  fetchInbox: vi.fn().mockResolvedValue(undefined),
  fetchMore: vi.fn().mockResolvedValue(undefined),
  fetchSummary: vi.fn().mockResolvedValue(undefined),
  fetchLabels: vi.fn().mockResolvedValue(undefined),
  snooze: vi.fn().mockResolvedValue(true),
  unsnooze: vi.fn().mockResolvedValue(true),
  archive: vi.fn().mockResolvedValue(true),
  unarchive: vi.fn().mockResolvedValue(true),
  switchTab: vi.fn().mockResolvedValue(undefined),
  setPriorityFilter: vi.fn().mockResolvedValue(undefined),
  setSourceTypeFilter: vi.fn().mockResolvedValue(undefined),
  setLabelFilter: vi.fn().mockResolvedValue(undefined),
  computeSnoozeUntil: vi.fn().mockReturnValue('2026-06-01T12:00:00Z'),
  toggleSelectionMode: vi.fn(),
  toggleSelect: vi.fn(),
  clearSelection: vi.fn(),
  runBulk: vi.fn().mockResolvedValue({ processed: 1, skipped: 0 }),
  assignLabel: vi.fn().mockResolvedValue(true),
  unassignLabel: vi.fn().mockResolvedValue(true),
  createLabel: vi.fn().mockResolvedValue({ id: 'label-1', name: 'test', color: '#ff0000', icon: null }),
  updateLabel: vi.fn().mockResolvedValue(undefined),
  deleteLabel: vi.fn().mockResolvedValue(undefined),
}

vi.mock('~/stores/useInboxStore', () => ({
  useInboxStore: () => storeMock,
}))

vi.mock('~/composables/useInboxApi', () => ({
  useInboxApi: () => ({
    getInbox: vi.fn(),
    getSummary: vi.fn(),
    snooze: vi.fn(),
    unsnooze: vi.fn(),
    archive: vi.fn(),
    unarchive: vi.fn(),
  }),
  priorityI18nKey: (p: string) => `inbox.priority.${p.toLowerCase()}`,
  prioritySeverity: () => 'danger',
  sourceTypeI18nKey: (s: string) => `inbox.source.${s.toLowerCase()}`,
  sourceTypeIcon: () => 'pi pi-bell',
}))

vi.mock('~/stores/useAuthStore', () => ({
  // app/plugins/auth.client.ts が mount 毎に loadFromStorage() を呼ぶため必須（#2609 是正）。
  useAuthStore: () => ({ user: { id: 1, timezone: 'Asia/Tokyo' }, isAuthenticated: false, loadFromStorage: vi.fn() }),
}))

vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: vi.fn(),
    error: vi.fn(),
  }),
}))

vi.mock('~/composables/useErrorReport', () => ({
  useErrorReport: () => ({
    captureQuiet: vi.fn(),
  }),
}))

vi.mock('~/composables/useRelativeTime', () => ({
  useRelativeTime: () => ({
    relativeTime: (d: string) => d,
  }),
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
    title: 'グループ通知',
    groupCount: 2,
    groupMembers,
    canonicalRef: 'BLOG_POST:5',
    ...overrides,
  })
}

// ──────────────────────────────────────────────
// テスト
// ──────────────────────────────────────────────

describe('InboxList.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    storeMock.items = []
    storeMock.currentTab = 'INBOX'
    storeMock.loading = false
    storeMock.hasMore = false
    storeMock.summaryByState = {}
    storeMock.priorityFilter = []
    storeMock.sourceTypeFilter = []
    storeMock.error = null
    // モックのリセット
    for (const key of Object.keys(storeMock)) {
      const fn = (storeMock as Record<string, unknown>)[key]
      if (typeof fn === 'function' && 'mockReset' in fn) {
        ;(fn as ReturnType<typeof vi.fn>).mockReset()
      }
    }
    storeMock.fetchInbox.mockResolvedValue(undefined)
    storeMock.fetchSummary.mockResolvedValue(undefined)
    storeMock.fetchLabels.mockResolvedValue(undefined)
    storeMock.snooze.mockResolvedValue(true)
    storeMock.archive.mockResolvedValue(true)
    storeMock.unsnooze.mockResolvedValue(true)
    storeMock.unarchive.mockResolvedValue(true)
    storeMock.switchTab.mockResolvedValue(undefined)
    storeMock.computeSnoozeUntil.mockReturnValue('2026-06-01T12:00:00Z')
    storeMock.runBulk.mockResolvedValue({ processed: 1, skipped: 0 })
  })

  // ──────────────────────────────────────────────
  // 状態タブ
  // ──────────────────────────────────────────────

  describe('状態タブ', () => {
    it('受信箱・スヌーズ中・保管庫の 3 タブが表示される', async () => {
      const wrapper = await mountSuspended(InboxList)
      const tabs = wrapper.findAll('[role="tab"]')
      expect(tabs).toHaveLength(3)
    })

    it('タブクリックで switchTab が呼ばれる', async () => {
      const wrapper = await mountSuspended(InboxList)
      const tabs = wrapper.findAll('[role="tab"]')
      await tabs[1]?.trigger('click')
      expect(storeMock.switchTab).toHaveBeenCalledWith('SNOOZED')
    })

    it('現在タブの data-testid が inbox-tab-inbox になっている', async () => {
      const wrapper = await mountSuspended(InboxList)
      const inboxTab = wrapper.find('[data-testid="inbox-tab-inbox"]')
      expect(inboxTab.exists()).toBe(true)
    })
  })

  // ──────────────────────────────────────────────
  // 空状態
  // ──────────────────────────────────────────────

  describe('空状態', () => {
    it('items が空で loading=false のとき空状態コンポーネントが表示される', async () => {
      storeMock.items = []
      storeMock.loading = false
      const wrapper = await mountSuspended(InboxList)
      const emptyState = wrapper.find('[data-testid="inbox-empty-state"]')
      expect(emptyState.exists()).toBe(true)
    })

    it('items がある場合は空状態コンポーネントが表示されない', async () => {
      storeMock.items = [makeItem()]
      const wrapper = await mountSuspended(InboxList)
      const emptyState = wrapper.find('[data-testid="inbox-empty-state"]')
      expect(emptyState.exists()).toBe(false)
    })
  })

  // ──────────────────────────────────────────────
  // archive ボタン
  // ──────────────────────────────────────────────

  describe('archive ボタン', () => {
    it('UNREAD アイテムの archive ボタンクリックでストアの archive が呼ばれる', async () => {
      storeMock.items = [makeItem({ id: 'NOTIFICATION:1', sourceType: 'NOTIFICATION', sourceId: 1, state: 'UNREAD' })]
      const wrapper = await mountSuspended(InboxList)

      const archiveBtn = wrapper.find('[data-testid="inbox-archive-btn-NOTIFICATION:1"]')
      expect(archiveBtn.exists()).toBe(true)
      await archiveBtn.trigger('click')

      expect(storeMock.archive).toHaveBeenCalledWith('NOTIFICATION', 1)
    })

    it('ARCHIVED アイテムには unarchive ボタンが表示される', async () => {
      storeMock.items = [makeItem({ id: 'NOTIFICATION:1', state: 'ARCHIVED' })]
      const wrapper = await mountSuspended(InboxList)

      const unarchiveBtn = wrapper.find('[data-testid="inbox-unarchive-btn-NOTIFICATION:1"]')
      expect(unarchiveBtn.exists()).toBe(true)
    })

    it('ARCHIVED アイテムには archive ボタンが表示されない', async () => {
      storeMock.items = [makeItem({ id: 'NOTIFICATION:1', state: 'ARCHIVED' })]
      const wrapper = await mountSuspended(InboxList)

      const archiveBtn = wrapper.find('[data-testid="inbox-archive-btn-NOTIFICATION:1"]')
      expect(archiveBtn.exists()).toBe(false)
    })
  })

  // ──────────────────────────────────────────────
  // snooze ボタン
  // ──────────────────────────────────────────────

  describe('snooze ボタン', () => {
    it('UNREAD アイテムの snooze ボタンが表示される', async () => {
      storeMock.items = [makeItem({ id: 'NOTIFICATION:1', state: 'UNREAD' })]
      const wrapper = await mountSuspended(InboxList)

      const snoozeBtn = wrapper.find('[data-testid="inbox-snooze-btn-NOTIFICATION:1"]')
      expect(snoozeBtn.exists()).toBe(true)
    })

    it('ARCHIVED アイテムには snooze ボタンが表示されない', async () => {
      storeMock.items = [makeItem({ id: 'NOTIFICATION:1', state: 'ARCHIVED' })]
      const wrapper = await mountSuspended(InboxList)

      const snoozeBtn = wrapper.find('[data-testid="inbox-snooze-btn-NOTIFICATION:1"]')
      expect(snoozeBtn.exists()).toBe(false)
    })
  })

  // ──────────────────────────────────────────────
  // unsnooze ボタン
  // ──────────────────────────────────────────────

  describe('unsnooze ボタン', () => {
    it('SNOOZED アイテムの unsnooze ボタンクリックでストアの unsnooze が呼ばれる', async () => {
      storeMock.items = [
        makeItem({
          id: 'NOTIFICATION:1',
          sourceType: 'NOTIFICATION',
          sourceId: 1,
          state: 'SNOOZED',
          snoozedUntil: '2026-06-01T12:00:00Z',
        }),
      ]
      const wrapper = await mountSuspended(InboxList)

      const unsnoozeBtn = wrapper.find('[data-testid="inbox-unsnooze-btn-NOTIFICATION:1"]')
      expect(unsnoozeBtn.exists()).toBe(true)
      await unsnoozeBtn.trigger('click')

      expect(storeMock.unsnooze).toHaveBeenCalledWith('NOTIFICATION', 1)
    })

    it('UNREAD アイテムには unsnooze ボタンが表示されない', async () => {
      storeMock.items = [makeItem({ id: 'NOTIFICATION:1', state: 'UNREAD' })]
      const wrapper = await mountSuspended(InboxList)

      const unsnoozeBtn = wrapper.find('[data-testid="inbox-unsnooze-btn-NOTIFICATION:1"]')
      expect(unsnoozeBtn.exists()).toBe(false)
    })
  })

  // ──────────────────────────────────────────────
  // onMounted
  // ──────────────────────────────────────────────

  describe('onMounted', () => {
    it('マウント時に fetchSummary / fetchInbox / fetchLabels が呼ばれる', async () => {
      await mountSuspended(InboxList)
      expect(storeMock.fetchSummary).toHaveBeenCalledTimes(1)
      expect(storeMock.fetchInbox).toHaveBeenCalledTimes(1)
      expect(storeMock.fetchLabels).toHaveBeenCalledTimes(1)
    })
  })

  // ──────────────────────────────────────────────
  // Phase 2: bulk 選択モード
  // ──────────────────────────────────────────────

  describe('bulk 選択モード', () => {
    it('bulk モードトグルボタンが表示される', async () => {
      const wrapper = await mountSuspended(InboxList)
      const btn = wrapper.find('[data-testid="inbox-bulk-mode-btn"]')
      expect(btn.exists()).toBe(true)
    })

    it('ラベル管理ボタンが表示される', async () => {
      const wrapper = await mountSuspended(InboxList)
      const btn = wrapper.find('[data-testid="inbox-label-manage-btn"]')
      expect(btn.exists()).toBe(true)
    })
  })

  // ──────────────────────────────────────────────
  // Phase 3: グループカード件数バッジ
  // ──────────────────────────────────────────────

  describe('Phase 3: グループカード件数バッジ', () => {
    it('groupCount > 1 のアイテムに件数バッジが表示される', async () => {
      storeMock.items = [makeGroupItem({ id: 'NOTIFICATION:10', groupCount: 3 })]
      const wrapper = await mountSuspended(InboxList)

      const badge = wrapper.find('[data-testid="inbox-group-badge-NOTIFICATION:10"]')
      expect(badge.exists()).toBe(true)
    })

    it('groupCount = 1 のアイテムには件数バッジが表示されない', async () => {
      storeMock.items = [makeItem({ id: 'NOTIFICATION:1', groupCount: 1 })]
      const wrapper = await mountSuspended(InboxList)

      const badge = wrapper.find('[data-testid="inbox-group-badge-NOTIFICATION:1"]')
      expect(badge.exists()).toBe(false)
    })

    it('groupCount 未定義（旧BE互換）のアイテムには件数バッジが表示されない', async () => {
      storeMock.items = [makeItem({ id: 'NOTIFICATION:1' })]
      const wrapper = await mountSuspended(InboxList)

      const badge = wrapper.find('[data-testid="inbox-group-badge-NOTIFICATION:1"]')
      expect(badge.exists()).toBe(false)
    })

    it('グループカードの archive ボタンクリックでストアの archive が呼ばれる', async () => {
      storeMock.items = [makeGroupItem()]
      storeMock.archive.mockResolvedValue(true)
      const wrapper = await mountSuspended(InboxList)

      const archiveBtn = wrapper.find('[data-testid="inbox-archive-btn-NOTIFICATION:10"]')
      expect(archiveBtn.exists()).toBe(true)
      await archiveBtn.trigger('click')

      // ストアの archive が groupItem の sourceType/sourceId で呼ばれる
      expect(storeMock.archive).toHaveBeenCalledWith('NOTIFICATION', 10)
    })
  })
})
