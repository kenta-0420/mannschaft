import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import ChatMessagePanel from '~/components/chat/ChatMessagePanel.vue'
import type { ChatChannelResponse, ChatChannelType } from '~/types/chat'

/**
 * CMP-261105-1507 欠陥3 の回帰（Codex 検分 P2）:
 * DM から Zimmer を作ると、作成 API の応答（viewer=null）がそのまま表示中チャンネルになる。
 * 既読の対象を viewer の有無で決めていたため、正規のメンバーなのに既読 API が一度も呼ばれなかった。
 * 対象はチャンネルの種別（メンバー行を持つ種別）で決める。
 */

const getMessages = vi.fn()
mockNuxtImport('useChatApi', () => () => ({
  getMessages,
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

const markChannelRead = vi.fn()
vi.mock('~/composables/chat/useChatReadSync', () => ({
  useChatReadSync: () => ({ markChannelRead, onChannelRead: () => () => undefined }),
}))
vi.mock('~/composables/chat/useChatMembershipInvite', () => ({
  useChatMembershipInvite: () => ({ joinInvite: vi.fn(), declineInvite: vi.fn() }),
}))
vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 }, isAuthenticated: false, loadFromStorage: vi.fn() }),
}))

function makeChannel(type: ChatChannelType, withViewer: boolean): ChatChannelResponse {
  return {
    id: 99,
    identity: { channelType: type, teamId: null, organizationId: null },
    meta: { name: 'room', iconKey: null, description: null },
    settings: { isPrivate: false, isInquiryChannel: false, isArchived: false, version: 1 },
    lastMessage: { lastMessageAt: null, lastMessagePreview: null },
    source: { sourceType: null, sourceId: null },
    audit: { createdBy: 42, createdAt: null, updatedAt: null },
    memberCount: 3,
    dmPartner: null,
    viewer: withViewer ? { unreadCount: 1, isMuted: false, isPinned: false, category: null, role: 'OWNER' } : null,
  } as ChatChannelResponse
}

beforeEach(() => {
  getMessages.mockReset().mockResolvedValue({ data: [], meta: { nextCursor: null, hasMore: false } })
  markChannelRead.mockReset().mockResolvedValue(undefined)
})

describe('ChatMessagePanel.vue（表示中チャンネルの既読化）', () => {
  it('作成直後の Zimmer（viewer=null の GROUP_DM）でも既読 API を呼ぶ', async () => {
    await mountSuspended(ChatMessagePanel, { props: { channel: makeChannel('GROUP_DM', false) }, shallow: true })
    await flushPromises()
    expect(markChannelRead).toHaveBeenCalledWith(99)
  })

  it('メンバー行を持たない種別（村ロビー）は viewer があっても既読 API を呼ばない', async () => {
    await mountSuspended(ChatMessagePanel, { props: { channel: makeChannel('VILLAGE_LOBBY', true) }, shallow: true })
    await flushPromises()
    expect(getMessages).toHaveBeenCalled()
    expect(markChannelRead).not.toHaveBeenCalled()
  })
})
