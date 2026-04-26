import { describe, it, expect, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatTabBar from '~/components/chat/ChatTabBar.vue'
import type { ChatTab, ChatChannelResponse } from '~/types/chat'

/**
 * F04.2.1 ChatTabBar.vue のユニットテスト
 *
 * テスト対象:
 * - タブ一覧が正しく表示される
 * - アクティブタブに border-primary クラスが付く
 * - × クリックで close イベントが発火する
 * - + ボタンクリックで openAdd イベントが発火する
 * - isMaxReached=true のとき + ボタンが disabled になる
 * - 右クリックで contextmenu イベントが発火する
 */

// === Mocks ===

// ChatTabItem は子コンポーネントとして動作するがテスト上はスタブ化しない
// （Vue Test Utils の mountSuspended で実際のコンポーネントツリーを使う）

vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ info: vi.fn(), warn: vi.fn(), error: vi.fn() }),
}))

// ─── テスト用ヘルパー ───────────────────────────────────────────────

function makeChannel(id: number, name: string): ChatChannelResponse {
  return {
    id,
    channelType: 'TEAM',
    team: { id: 10, name: 'テストチーム' },
    organization: null,
    name,
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
  }
}

function makeTab(id: string, channelId: number, channelName: string): ChatTab {
  return {
    id,
    channelId,
    channel: makeChannel(channelId, channelName),
    createdAt: Date.now(),
  }
}

// ─── テストスイート ─────────────────────────────────────────────────

describe('ChatTabBar.vue', () => {
  /**
   * UNIT-CHAT-TAB-BAR-001
   * タブ一覧が正しく表示される
   */
  it('UNIT-CHAT-TAB-BAR-001: タブ一覧が正しく表示される', async () => {
    const tabs: ChatTab[] = [
      makeTab('tab-1', 1, '全体連絡'),
      makeTab('tab-2', 2, '開発チーム'),
      makeTab('tab-3', 3, '雑談'),
    ]

    const wrapper = await mountSuspended(ChatTabBar, {
      props: {
        tabs,
        activeTabId: 'tab-1',
        isMaxReached: false,
      },
    })

    // 3つのタブが role="tab" でレンダリングされていることを確認
    const tabElements = wrapper.findAll('[role="tab"]')
    expect(tabElements).toHaveLength(3)

    // チャンネル名が含まれていることを確認
    expect(wrapper.text()).toContain('全体連絡')
    expect(wrapper.text()).toContain('開発チーム')
    expect(wrapper.text()).toContain('雑談')
  })

  /**
   * UNIT-CHAT-TAB-BAR-002
   * アクティブタブに border-primary クラスが付く
   */
  it('UNIT-CHAT-TAB-BAR-002: アクティブタブに border-primary クラスが付く', async () => {
    const tabs: ChatTab[] = [
      makeTab('tab-1', 1, '全体連絡'),
      makeTab('tab-2', 2, '開発チーム'),
    ]

    const wrapper = await mountSuspended(ChatTabBar, {
      props: {
        tabs,
        activeTabId: 'tab-1',
        isMaxReached: false,
      },
    })

    const tabElements = wrapper.findAll('[role="tab"]')
    expect(tabElements[0]?.classes()).toContain('border-primary')
    expect(tabElements[1]?.classes()).not.toContain('border-primary')
  })

  /**
   * UNIT-CHAT-TAB-BAR-003
   * × クリックで close イベントが発火する
   */
  it('UNIT-CHAT-TAB-BAR-003: × クリックで close イベントが発火する', async () => {
    const tabs: ChatTab[] = [makeTab('tab-1', 1, '全体連絡')]

    const wrapper = await mountSuspended(ChatTabBar, {
      props: {
        tabs,
        activeTabId: 'tab-1',
        isMaxReached: false,
      },
    })

    // 閉じるボタン（aria-label に "close" が含まれるボタン）をクリック
    const closeBtn = wrapper.find('button[aria-label]')
    // × ボタンは tabitem 内にある
    const tabItem = wrapper.find('[role="tab"]')
    const closeBtnInTab = tabItem.find('button')
    await closeBtnInTab.trigger('click')

    // close イベントが発火したことを確認
    expect(wrapper.emitted('close')).toBeTruthy()
    expect(wrapper.emitted('close')?.[0]).toEqual(['tab-1'])
  })

  /**
   * UNIT-CHAT-TAB-BAR-004
   * + ボタンクリックで openAdd イベントが発火する
   */
  it('UNIT-CHAT-TAB-BAR-004: + ボタンクリックで openAdd イベントが発火する', async () => {
    const tabs: ChatTab[] = [makeTab('tab-1', 1, '全体連絡')]

    const wrapper = await mountSuspended(ChatTabBar, {
      props: {
        tabs,
        activeTabId: 'tab-1',
        isMaxReached: false,
      },
    })

    // + ボタンは sticky 領域にある（role="tablist" 外の button）
    // isMaxReached=false の場合、disabled ではない button を探す
    const buttons = wrapper.findAll('button')
    // + ボタンは最後の button（sticky 右側）
    const addButton = buttons[buttons.length - 1]
    expect(addButton).toBeTruthy()
    await addButton!.trigger('click')

    expect(wrapper.emitted('openAdd')).toBeTruthy()
  })

  /**
   * UNIT-CHAT-TAB-BAR-005
   * isMaxReached=true のとき + ボタンが disabled になる
   */
  it('UNIT-CHAT-TAB-BAR-005: isMaxReached=true のとき + ボタンが disabled になる', async () => {
    const tabs: ChatTab[] = Array.from({ length: 10 }, (_, i) =>
      makeTab(`tab-${i + 1}`, i + 1, `チャンネル${i + 1}`),
    )

    const wrapper = await mountSuspended(ChatTabBar, {
      props: {
        tabs,
        activeTabId: 'tab-1',
        isMaxReached: true,
      },
    })

    // + ボタンが disabled かつ aria-disabled="true" であることを確認
    const allButtons = wrapper.findAll('button')
    // 最後のボタンが + ボタン（sticky 領域）
    const addButton = allButtons[allButtons.length - 1]
    expect(addButton).toBeTruthy()
    expect((addButton!.element as HTMLButtonElement).disabled).toBe(true)
    expect(addButton!.attributes('aria-disabled')).toBe('true')
  })

  /**
   * UNIT-CHAT-TAB-BAR-006
   * 右クリックで contextmenu イベントが発火する
   */
  it('UNIT-CHAT-TAB-BAR-006: 右クリックで contextmenu イベントが発火する', async () => {
    const tabs: ChatTab[] = [makeTab('tab-1', 1, '全体連絡')]

    const wrapper = await mountSuspended(ChatTabBar, {
      props: {
        tabs,
        activeTabId: 'tab-1',
        isMaxReached: false,
      },
    })

    // タブアイテムで右クリック（contextmenu イベント）をトリガー
    const tabElement = wrapper.find('[role="tab"]')
    await tabElement.trigger('contextmenu', { clientX: 100, clientY: 50 })

    // contextmenu イベントが発火したことを確認
    expect(wrapper.emitted('contextmenu')).toBeTruthy()
    const payload = wrapper.emitted('contextmenu')?.[0]?.[0] as {
      tabId: string
      x: number
      y: number
    }
    expect(payload.tabId).toBe('tab-1')
    expect(typeof payload.x).toBe('number')
    expect(typeof payload.y).toBe('number')
  })

  /**
   * UNIT-CHAT-TAB-BAR-007
   * タブが0件のとき、タブアイテムが表示されず + ボタンのみ表示される
   */
  it('UNIT-CHAT-TAB-BAR-007: タブが0件のとき + ボタンのみ表示される', async () => {
    const wrapper = await mountSuspended(ChatTabBar, {
      props: {
        tabs: [],
        activeTabId: null,
        isMaxReached: false,
      },
    })

    const tabElements = wrapper.findAll('[role="tab"]')
    expect(tabElements).toHaveLength(0)

    // + ボタンは表示されている
    const buttons = wrapper.findAll('button')
    expect(buttons.length).toBeGreaterThan(0)
  })

  /**
   * UNIT-CHAT-TAB-BAR-008
   * タブクリックで switch イベントが発火する
   */
  it('UNIT-CHAT-TAB-BAR-008: タブクリックで switch イベントが発火する', async () => {
    const tabs: ChatTab[] = [
      makeTab('tab-1', 1, '全体連絡'),
      makeTab('tab-2', 2, '開発チーム'),
    ]

    const wrapper = await mountSuspended(ChatTabBar, {
      props: {
        tabs,
        activeTabId: 'tab-1',
        isMaxReached: false,
      },
    })

    // 2番目のタブをクリック
    const tabElements = wrapper.findAll('[role="tab"]')
    await tabElements[1]!.trigger('click')

    // switch イベントが発火したことを確認
    expect(wrapper.emitted('switch')).toBeTruthy()
    expect(wrapper.emitted('switch')?.[0]).toEqual(['tab-2'])
  })
})
