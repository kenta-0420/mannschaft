import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { useEventBus } from '@vueuse/core'
import ChatMessagePanel from '~/components/chat/ChatMessagePanel.vue'
import ChatMessageInput from '~/components/chat/ChatMessageInput.vue'
import { useChatListSync } from '~/composables/chat/useChatListSync'
import type { ChatChannelResponse } from '~/types/chat'

/**
 * CMP-261105-1507: 自分の送信と他者の新着（WS MESSAGE_CREATED）でも、部屋一覧へ知らせる。
 * 知らせないと一覧のプレビュー（最後の発言）が古いまま残り、再読込まで直らない。
 */

mockNuxtImport('useChatApi', () => () => ({
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
vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 }, isAuthenticated: false, loadFromStorage: vi.fn() }),
}))

const CHANNEL = {
  id: 77,
  identity: { channelType: 'GROUP_DM', teamId: null, organizationId: null },
  meta: { name: 'room', iconKey: null, description: null },
  settings: { isPrivate: false, isInquiryChannel: false, isArchived: false, version: 1 },
  lastMessage: { lastMessageAt: null, lastMessagePreview: null },
  source: { sourceType: null, sourceId: null },
  audit: { createdBy: 42, createdAt: null, updatedAt: null },
  memberCount: 2,
  dmPartner: null,
  viewer: { unreadCount: 0, isMuted: false, isPinned: false, category: null, role: 'OWNER' },
} as ChatChannelResponse

const changed = vi.fn()
let off: (() => void) | undefined

beforeEach(() => {
  changed.mockReset()
  off?.()
  off = useChatListSync().onChannelContentChanged(changed)
})

describe('ChatMessagePanel.vue（一覧への内容変更の通知）', () => {
  it('送信成功（ChatMessageInput の sent）で一覧へ通知する', async () => {
    const wrapper = await mountSuspended(ChatMessagePanel, { props: { channel: CHANNEL }, shallow: true })
    await flushPromises()
    wrapper.findComponent(ChatMessageInput).vm.$emit('sent')
    await flushPromises()
    expect(changed.mock.calls.map((c) => c[0])).toContain(77)
  })

  it('WS の MESSAGE_CREATED を受けたら一覧へ通知する', async () => {
    await mountSuspended(ChatMessagePanel, { props: { channel: CHANNEL }, shallow: true })
    await flushPromises()
    useEventBus<{ type: string; data: unknown }>('chat:ws:event').emit({
      type: 'MESSAGE_CREATED',
      data: { id: 9001, channelId: 77, body: 'こんにちは', sender: { id: 5, displayName: 'A' }, createdAt: '2026-11-13T00:00:00' },
    })
    await flushPromises()
    expect(changed.mock.calls.map((c) => c[0])).toContain(77)
  })
})
