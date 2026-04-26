/**
 * F04.2.1: useChatTabsStore のユニットテスト
 *
 * テスト対象:
 * - addTab: 正常追加、上限10個ガード、重複タブ許可
 * - closeTab: 削除後のアクティブタブ移動（前→次→null）、存在しないIDは無視
 * - switchTab: アクティブタブ切替
 * - closeOtherTabs / closeRightTabs / clearAll
 * - openChannelIds: 重複チャンネルでも Set 内は1個
 * - restore: API取得成功/失敗、activeTabId フォールバック
 */
import { describe, it, expect, beforeEach, vi, type MockedFunction } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useChatTabsStore } from '~/stores/useChatTabsStore'
import type { ChatChannelResponse } from '~/types/chat'

// === モック: useAuthStore ===
vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 99 } }),
}))

// === モック: useNotification ===
const notificationInfoMock = vi.fn()
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ info: notificationInfoMock }),
}))

// === モック: useChatApi ===
const getChannelMock: MockedFunction<(id: number) => Promise<{ data: ChatChannelResponse }>> =
  vi.fn()
vi.mock('~/composables/useChatApi', () => ({
  useChatApi: () => ({ getChannel: getChannelMock }),
}))

// === モック: useChatTabsPersistence ===
const persistenceSaveMock = vi.fn()
const persistenceLoadMock: MockedFunction<
  () => { tabs: { id: string; channelId: number }[]; activeTabId: string | null } | null
> = vi.fn()
const persistenceClearMock = vi.fn()

vi.mock('~/composables/useChatTabsPersistence', () => ({
  useChatTabsPersistence: () => ({
    save: persistenceSaveMock,
    load: persistenceLoadMock,
    clear: persistenceClearMock,
  }),
}))

// === テスト用ヘルパー ===

function makeChannel(overrides: Partial<ChatChannelResponse> = {}): ChatChannelResponse {
  return {
    id: 1,
    channelType: 'TEAM',
    team: { id: 10, name: 'テストチーム' },
    organization: null,
    name: 'テストチャンネル',
    iconUrl: null,
    description: null,
    isPrivate: false,
    isArchived: false,
    lastMessageAt: null,
    lastMessagePreview: null,
    unreadCount: 0,
    isMuted: false,
    isPinned: false,
    memberCount: 5,
    dmPartner: null,
    sourceType: null,
    sourceId: null,
    ...overrides,
  }
}

/** UUID v4 形式の文字列を生成（テスト用） */
function makeUuidV4(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

describe('useChatTabsStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    persistenceSaveMock.mockReset()
    persistenceLoadMock.mockReset()
    persistenceClearMock.mockReset()
    getChannelMock.mockReset()
    notificationInfoMock.mockReset()
  })

  // ===========================================================
  // addTab
  // ===========================================================
  describe('addTab', () => {
    it('正常系: チャンネルを追加するとタブが1件追加されアクティブになる', () => {
      const store = useChatTabsStore()
      const channel = makeChannel({ id: 1, name: '全体連絡' })

      const result = store.addTab(channel)

      expect(result.ok).toBe(true)
      expect(store.tabs).toHaveLength(1)
      expect(store.tabs[0]!.channelId).toBe(1)
      expect(store.tabs[0]!.channel.name).toBe('全体連絡')
      expect(store.activeTabId).toBe(store.tabs[0]!.id)
    })

    it('重複タブ許可: 同じチャンネルを2回追加できる', () => {
      const store = useChatTabsStore()
      const channel = makeChannel({ id: 42 })

      store.addTab(channel)
      store.addTab(channel)

      expect(store.tabs).toHaveLength(2)
      expect(store.tabs[0]!.channelId).toBe(42)
      expect(store.tabs[1]!.channelId).toBe(42)
      // id は異なる
      expect(store.tabs[0]!.id).not.toBe(store.tabs[1]!.id)
    })

    it('上限ガード: 10個になった状態でもう1つ追加すると MAX_TABS_REACHED を返す', () => {
      const store = useChatTabsStore()

      for (let i = 1; i <= 10; i++) {
        const result = store.addTab(makeChannel({ id: i }))
        expect(result.ok).toBe(true)
      }
      expect(store.tabs).toHaveLength(10)

      const result = store.addTab(makeChannel({ id: 11 }))
      expect(result.ok).toBe(false)
      expect(result.reason).toBe('MAX_TABS_REACHED')
      expect(store.tabs).toHaveLength(10)
    })

    it('追加後に persist が呼ばれる', () => {
      const store = useChatTabsStore()
      store.addTab(makeChannel({ id: 1 }))
      expect(persistenceSaveMock).toHaveBeenCalledTimes(1)
    })
  })

  // ===========================================================
  // closeTab
  // ===========================================================
  describe('closeTab', () => {
    it('指定タブを削除する', () => {
      const store = useChatTabsStore()
      store.addTab(makeChannel({ id: 1 }))
      store.addTab(makeChannel({ id: 2 }))
      const firstId = store.tabs[0]!.id

      store.closeTab(firstId)

      expect(store.tabs).toHaveLength(1)
      expect(store.tabs[0]!.channelId).toBe(2)
    })

    it('アクティブタブを閉じると直前（左）のタブがアクティブになる', () => {
      const store = useChatTabsStore()
      store.addTab(makeChannel({ id: 1 }))
      store.addTab(makeChannel({ id: 2 }))
      store.addTab(makeChannel({ id: 3 }))
      const ids = store.tabs.map((t) => t.id)

      // 2番目をアクティブにして閉じる
      store.switchTab(ids[1]!)
      store.closeTab(ids[1]!)

      // 直前（インデックス0）がアクティブ
      expect(store.activeTabId).toBe(ids[0]!)
      expect(store.tabs).toHaveLength(2)
    })

    it('最後の1枚を閉じると activeTabId が null になる', () => {
      const store = useChatTabsStore()
      store.addTab(makeChannel({ id: 1 }))
      const tabId = store.tabs[0]!.id

      store.closeTab(tabId)

      expect(store.tabs).toHaveLength(0)
      expect(store.activeTabId).toBeNull()
    })

    it('存在しない tabId を渡しても何もしない', () => {
      const store = useChatTabsStore()
      store.addTab(makeChannel({ id: 1 }))

      expect(() => store.closeTab('non-existent-id')).not.toThrow()
      expect(store.tabs).toHaveLength(1)
    })
  })

  // ===========================================================
  // closeOtherTabs
  // ===========================================================
  describe('closeOtherTabs', () => {
    it('指定タブ以外がすべて削除される', () => {
      const store = useChatTabsStore()
      store.addTab(makeChannel({ id: 1 }))
      store.addTab(makeChannel({ id: 2 }))
      store.addTab(makeChannel({ id: 3 }))
      const secondId = store.tabs[1]!.id

      store.closeOtherTabs(secondId)

      expect(store.tabs).toHaveLength(1)
      expect(store.tabs[0]!.id).toBe(secondId)
      expect(store.activeTabId).toBe(secondId)
    })
  })

  // ===========================================================
  // closeRightTabs
  // ===========================================================
  describe('closeRightTabs', () => {
    it('指定タブより右側のタブがすべて削除される', () => {
      const store = useChatTabsStore()
      store.addTab(makeChannel({ id: 1 }))
      store.addTab(makeChannel({ id: 2 }))
      store.addTab(makeChannel({ id: 3 }))
      const ids = store.tabs.map((t) => t.id)

      store.closeRightTabs(ids[1]!)

      expect(store.tabs).toHaveLength(2)
      expect(store.tabs[0]!.id).toBe(ids[0]!)
      expect(store.tabs[1]!.id).toBe(ids[1]!)
    })

    it('存在しない ID を指定しても何もしない', () => {
      const store = useChatTabsStore()
      store.addTab(makeChannel({ id: 1 }))

      expect(() => store.closeRightTabs('non-existent-id')).not.toThrow()
      expect(store.tabs).toHaveLength(1)
    })
  })

  // ===========================================================
  // switchTab
  // ===========================================================
  describe('switchTab', () => {
    it('activeTabId が変わる', () => {
      const store = useChatTabsStore()
      store.addTab(makeChannel({ id: 1 }))
      store.addTab(makeChannel({ id: 2 }))
      const firstId = store.tabs[0]!.id

      store.switchTab(firstId)

      expect(store.activeTabId).toBe(firstId)
    })

    it('存在しないタブ ID を渡してもアクティブは変わらない', () => {
      const store = useChatTabsStore()
      store.addTab(makeChannel({ id: 1 }))
      const firstId = store.tabs[0]!.id

      store.switchTab('non-existent-id')

      expect(store.activeTabId).toBe(firstId)
    })
  })

  // ===========================================================
  // clearAll
  // ===========================================================
  describe('clearAll', () => {
    it('tabs が空になり activeTabId が null になる', () => {
      const store = useChatTabsStore()
      store.addTab(makeChannel({ id: 1 }))
      store.addTab(makeChannel({ id: 2 }))

      store.clearAll()

      expect(store.tabs).toHaveLength(0)
      expect(store.activeTabId).toBeNull()
    })

    it('ストレージもクリアされる（auth.user.id = 99 で clear が呼ばれる）', () => {
      const store = useChatTabsStore()
      store.addTab(makeChannel({ id: 1 }))

      store.clearAll()

      expect(persistenceClearMock).toHaveBeenCalledWith(99)
    })
  })

  // ===========================================================
  // restore
  // ===========================================================
  describe('restore', () => {
    it('正常系: 有効なタブデータが復元される', async () => {
      const store = useChatTabsStore()
      const uuid1 = makeUuidV4()
      const uuid2 = makeUuidV4()

      persistenceLoadMock.mockReturnValue({
        tabs: [
          { id: uuid1, channelId: 1 },
          { id: uuid2, channelId: 2 },
        ],
        activeTabId: uuid1,
      })

      getChannelMock.mockImplementation(async (id: number) => ({
        data: makeChannel({ id }),
      }))

      await store.restore()

      expect(store.tabs).toHaveLength(2)
      expect(store.tabs[0]!.id).toBe(uuid1)
      expect(store.tabs[1]!.id).toBe(uuid2)
      expect(store.activeTabId).toBe(uuid1)
    })

    it('API 取得失敗したタブはスキップされる', async () => {
      const store = useChatTabsStore()
      const uuid1 = makeUuidV4()
      const uuid2 = makeUuidV4()

      persistenceLoadMock.mockReturnValue({
        tabs: [
          { id: uuid1, channelId: 1 },
          { id: uuid2, channelId: 999 }, // 存在しないチャンネル
        ],
        activeTabId: uuid1,
      })

      getChannelMock.mockImplementation(async (id: number) => {
        if (id === 999) throw new Error('404 Not Found')
        return { data: makeChannel({ id }) }
      })

      await store.restore()

      expect(store.tabs).toHaveLength(1)
      expect(store.tabs[0]!.id).toBe(uuid1)
    })

    it('保存された activeTabId が復元タブに存在しない場合は先頭タブにフォールバックする', async () => {
      const store = useChatTabsStore()
      const uuid1 = makeUuidV4()
      const orphanUuid = makeUuidV4() // 復元されないタブ

      persistenceLoadMock.mockReturnValue({
        tabs: [{ id: uuid1, channelId: 1 }],
        activeTabId: orphanUuid, // 存在しない ID
      })

      getChannelMock.mockResolvedValue({ data: makeChannel({ id: 1 }) })

      await store.restore()

      expect(store.activeTabId).toBe(uuid1)
    })

    it('localStorage が空なら何もしない', async () => {
      const store = useChatTabsStore()
      persistenceLoadMock.mockReturnValue(null)

      await store.restore()

      expect(store.tabs).toHaveLength(0)
      expect(getChannelMock).not.toHaveBeenCalled()
    })
  })
})
