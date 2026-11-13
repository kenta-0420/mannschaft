import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import ChatIndexPage from '~/pages/chat/index.vue'
import TeamChatPage from '~/pages/teams/[slug]/chat.vue'
import OrgChatPage from '~/pages/organizations/[slug]/chat.vue'
import ChatMessagePanel from '~/components/chat/ChatMessagePanel.vue'
import ChatCreateDialog from '~/components/chat/ChatCreateDialog.vue'
import type { ChatChannelResponse } from '~/types/chat'

/**
 * CMP-261105-1507: DM を開始（または Zimmer を作成）しても、その会話が右ペインに開かず
 * 直前の部屋のまま残り、入力した文が誤って直前の部屋へ送られていた。
 * ChatCreateDialog の openDm / openChannel を各画面で受け、その会話を開く。
 * 一覧の取り直しが済む前に選ぶと一覧に無いので、取り直してから選び、無ければ詳細 API で取り直して開く。
 */

const getChannel = vi.fn()
mockNuxtImport('useChatApi', () => () => ({
  getChannel,
  getMessages: vi.fn().mockResolvedValue({ data: [], meta: { nextCursor: null, hasMore: false } }),
  sendTyping: vi.fn(),
  addReaction: vi.fn(),
  removeReaction: vi.fn(),
  togglePin: vi.fn(),
  editMessage: vi.fn(),
  deleteMessage: vi.fn(),
  bookmarkMessage: vi.fn(),
  removeBookmark: vi.fn(),
  getActiveThreads: vi.fn().mockResolvedValue({ data: [], meta: { nextCursor: null, hasMore: false } }),
  subscribeChannel: vi.fn(),
  unsubscribeChannel: vi.fn(),
  wsConnectionFailed: ref(false),
  wsUnauthenticated: ref(false),
  isChannelDenied: () => false,
}))
mockNuxtImport('useChatChannelEventListener', () => () => undefined)
mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: ref(false),
  isAdminOrDeputy: ref(false),
  loadPermissions: vi.fn(),
}))
mockNuxtImport('useIsDesktop', () => () => ({ isDesktop: ref(true) }))
vi.mock('~/composables/chat/useChatReadSync', () => ({
  useChatReadSync: () => ({ markChannelRead: vi.fn().mockResolvedValue(undefined), onChannelRead: () => () => undefined }),
}))
vi.mock('~/composables/chat/useChatMembershipInvite', () => ({
  useChatMembershipInvite: () => ({ joinInvite: vi.fn(), declineInvite: vi.fn() }),
}))
vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 }, isAuthenticated: false, loadFromStorage: vi.fn() }),
}))

function channel(id: number): ChatChannelResponse {
  return {
    id,
    identity: { channelType: 'DM', teamId: null, organizationId: null },
    meta: { name: `room-${id}`, iconKey: null, description: null },
    settings: { isPrivate: false, isInquiryChannel: false, isArchived: false, version: 1 },
    lastMessage: { lastMessageAt: null, lastMessagePreview: null },
    source: { sourceType: null, sourceId: null },
    audit: { createdBy: 42, createdAt: null, updatedAt: null },
    memberCount: 2,
    dmPartner: null,
    viewer: { unreadCount: 0, isMuted: false, isPinned: false, category: null, role: 'OWNER' },
  } as ChatChannelResponse
}

const PREVIOUS = channel(1)
const OPENED = channel(7)

const refresh = vi.fn()
const selectIfPresent = vi.fn()

/** 一覧のスタブ。実物と同じく selectIfPresent / refresh を公開する */
const ChatChannelListStub = defineComponent({
  name: 'ChatChannelList',
  emits: ['select', 'create', 'loaded'],
  setup(_props, { expose }) {
    expose({ refresh, selectIfPresent, refreshAndSelect: vi.fn() })
    return () => h('div')
  },
})

beforeEach(() => {
  localStorage.clear() // 前のテストが保存したタブを復元しない
  // Nuxt の Pinia はテスト間で共有されるため、前のテストが開いたタブを閉じる
  const store = useChatTabsStore()
  while (store.tabs.length > 0) store.closeTab(store.tabs[0]!.id)
  getChannel.mockReset()
  refresh.mockReset().mockResolvedValue(undefined)
  selectIfPresent.mockReset()
})

async function mountPage(page: typeof TeamChatPage) {
  const wrapper = await mountSuspended(page, {
    shallow: true,
    global: { stubs: { ChatChannelList: ChatChannelListStub } },
  })
  await flushPromises()
  wrapper.findComponent({ name: 'ChatChannelList' }).vm.$emit('select', PREVIOUS)
  await flushPromises()
  return wrapper
}

describe.each([
  ['チームのチャット', TeamChatPage],
  ['組織のチャット', OrgChatPage],
])('%s: 作成ダイアログが開いた会話を右ペインに開く', (_label, page) => {
  it.each(['openDm', 'openChannel'])('%s: 一覧を取り直してから、その会話を選ぶ', async (eventName) => {
    selectIfPresent.mockReturnValue(true)
    const wrapper = await mountPage(page)

    wrapper.findComponent(ChatCreateDialog).vm.$emit(eventName, 7)
    await flushPromises()

    expect(refresh).toHaveBeenCalled()
    expect(selectIfPresent).toHaveBeenCalledWith(7)
    expect(refresh.mock.invocationCallOrder[0]).toBeLessThan(selectIfPresent.mock.invocationCallOrder[0]!)
  })

  it('一覧に無いときは詳細 API で取り直して右ペインに開く（直前の部屋のまま残さない）', async () => {
    selectIfPresent.mockReturnValue(false)
    getChannel.mockResolvedValue({ data: OPENED })
    const wrapper = await mountPage(page)

    wrapper.findComponent(ChatCreateDialog).vm.$emit('openDm', 7)
    await flushPromises()

    expect(getChannel).toHaveBeenCalledWith(7)
    const shown = wrapper.findComponent(ChatMessagePanel).props('channel') as ChatChannelResponse
    expect(shown.id).toBe(7)
  })
})

describe('チャットのトップ画面: 作成ダイアログが開いた会話をタブで開く', () => {
  it.each(['openDm', 'openChannel'])('%s: 一覧に無ければ詳細 API で取り直してタブに追加する', async (eventName) => {
    selectIfPresent.mockReturnValue(false)
    getChannel.mockResolvedValue({ data: OPENED })
    const wrapper = await mountSuspended(ChatIndexPage, {
      shallow: true,
      global: { stubs: { ChatChannelList: ChatChannelListStub } },
    })
    await flushPromises()

    wrapper.findComponent(ChatCreateDialog).vm.$emit(eventName, 7)
    await flushPromises()

    expect(refresh).toHaveBeenCalled()
    expect(getChannel).toHaveBeenCalledWith(7)
    const store = useChatTabsStore()
    expect(store.tabs.map((t) => t.channelId)).toContain(7)
    expect(store.activeTabId).toBe(store.tabs.find((t) => t.channelId === 7)?.id)
  })

  it('一覧にあれば一覧の選択（select）経由で開く（二重に取得しない）', async () => {
    selectIfPresent.mockReturnValue(true)
    const wrapper = await mountSuspended(ChatIndexPage, {
      shallow: true,
      global: { stubs: { ChatChannelList: ChatChannelListStub } },
    })
    await flushPromises()

    wrapper.findComponent(ChatCreateDialog).vm.$emit('openDm', 7)
    await flushPromises()

    expect(selectIfPresent).toHaveBeenCalledWith(7)
    expect(getChannel).not.toHaveBeenCalled()
  })
})
