import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import TeamChatPage from '~/pages/teams/[slug]/chat.vue'
import OrgChatPage from '~/pages/organizations/[slug]/chat.vue'
import ChatMessagePanel from '~/components/chat/ChatMessagePanel.vue'
import ChatMessageList from '~/components/chat/message-panel/ChatMessageList.vue'
import type { ChatChannelResponse } from '~/types/chat'

/**
 * CMP-261105-1507（Codex 検分 P2 の残件）:
 * DM から Zimmer を作ると、作成 API の応答（viewer=null）がそのまま表示中のチャンネルになっていた。
 * 作成者は OWNER なのに viewer.role が無く、他人の投稿の削除が出なかった。
 * 作成直後は詳細 API で取り直してから表示中のチャンネルにする。取り直しの失敗は通知する。
 */

const getChannel = vi.fn()
const showError = vi.fn()
mockNuxtImport('useChatApi', () => () => ({
  getChannel,
  // 以下は ChatMessagePanel 単体の描画に要る API
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
vi.mock('~/composables/chat/useChatReadSync', () => ({
  useChatReadSync: () => ({ markChannelRead: vi.fn().mockResolvedValue(undefined), onChannelRead: () => () => undefined }),
}))
vi.mock('~/composables/chat/useChatMembershipInvite', () => ({
  useChatMembershipInvite: () => ({ joinInvite: vi.fn(), declineInvite: vi.fn() }),
}))
mockNuxtImport('useNotification', () => () => ({ showError, showSuccess: vi.fn() }))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: ref(false),
  isAdminOrDeputy: ref(false),
  loadPermissions: vi.fn(),
}))
mockNuxtImport('useIsDesktop', () => () => ({ isDesktop: ref(true) }))
vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 }, isAuthenticated: false, loadFromStorage: vi.fn() }),
}))

function channel(id: number, role: 'OWNER' | null): ChatChannelResponse {
  return {
    id,
    identity: { channelType: id === 1 ? 'DM' : 'GROUP_DM', teamId: null, organizationId: null },
    meta: { name: `room-${id}`, iconKey: null, description: null },
    settings: { isPrivate: false, isInquiryChannel: false, isArchived: false, version: 1 },
    lastMessage: { lastMessageAt: null, lastMessagePreview: null },
    source: { sourceType: null, sourceId: null },
    audit: { createdBy: 42, createdAt: null, updatedAt: null },
    memberCount: role ? 3 : 0,
    dmPartner: null,
    viewer: role ? { unreadCount: 0, isMuted: false, isPinned: false, category: null, role } : null,
  } as ChatChannelResponse
}

const KABINE = channel(1, 'OWNER')
/** inviteToZimmer の応答（BE の toChannelResponse は viewer を持たない） */
const CREATED_ZIMMER = channel(2, null)
/** 詳細 API の応答（作成者のメンバー行を含む） */
const DETAIL_ZIMMER = channel(2, 'OWNER')

beforeEach(() => {
  getChannel.mockReset()
  showError.mockReset()
})

/** 一覧のスタブ（ページが ref 経由で呼ぶ refresh / refreshAndSelect を公開する） */
const ChatChannelListStub = defineComponent({
  name: 'ChatChannelList',
  emits: ['select', 'create', 'loaded'],
  setup(_props, { expose }) {
    expose({ refresh: vi.fn(), refreshAndSelect: vi.fn().mockResolvedValue(undefined) })
    return () => h('div')
  },
})

async function mountAndCreate(page: typeof TeamChatPage) {
  const wrapper = await mountSuspended(page, {
    shallow: true,
    global: { stubs: { ChatChannelList: ChatChannelListStub } },
  })
  await flushPromises()
  wrapper.findComponent({ name: 'ChatChannelList' }).vm.$emit('select', KABINE)
  await flushPromises()
  wrapper.findComponent(ChatMessagePanel).vm.$emit('channelCreated', CREATED_ZIMMER)
  await flushPromises()
  return wrapper
}

describe.each([
  ['チームのチャット', TeamChatPage],
  ['組織のチャット', OrgChatPage],
])('%s: 作成直後のチャンネルは詳細 API で取り直して表示する', (_label, page) => {
  it('取り直した詳細（viewer.role=OWNER）を表示中のチャンネルにする', async () => {
    getChannel.mockResolvedValue({ data: DETAIL_ZIMMER })
    const wrapper = await mountAndCreate(page)

    expect(getChannel).toHaveBeenCalledWith(2)
    const shown = wrapper.findComponent(ChatMessagePanel).props('channel') as ChatChannelResponse
    expect(shown.id).toBe(2)
    expect(shown.viewer?.role).toBe('OWNER')
  })

  it('取り直しに失敗したら通知し、viewer の無い応答を表示中にしない', async () => {
    getChannel.mockRejectedValue(new Error('network'))
    const wrapper = await mountAndCreate(page)

    expect(showError).toHaveBeenCalled()
    const shown = wrapper.findComponent(ChatMessagePanel).props('channel') as ChatChannelResponse
    expect(shown.viewer).not.toBeNull()
  })
})

describe('ChatMessagePanel: 作成者（OWNER）には他人の投稿の削除を出す', () => {
  it('viewer.role=OWNER のチャンネルでは一覧へ can-delete=true を渡す', async () => {
    const wrapper = await mountSuspended(ChatMessagePanel, {
      props: { channel: DETAIL_ZIMMER },
      shallow: true,
    })
    await flushPromises()
    expect(wrapper.findComponent(ChatMessageList).props('canDelete')).toBe(true)
  })
})
