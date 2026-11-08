import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ChatChannelList from '~/components/chat/ChatChannelList.vue'
import { useChatReadSync } from '~/composables/chat/useChatReadSync'
import type { ChatChannelResponse } from '~/types/chat'

/**
 * CMP-261105-1507 欠陥3・4:
 * - 既読成功の通知を受けたら、そのチャンネルの未読バッジを消す（一覧は onMounted で一度しか取得しないため）
 * - 見出し・空状態の文言はロケールから引く（直書きしない）
 */

const getChannels = vi.fn()
const markAsRead = vi.fn()
mockNuxtImport('useChatApi', () => () => ({ getChannels, markAsRead }))

vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 }, isAuthenticated: false, loadFromStorage: vi.fn() }),
}))

function makeChannel(id: number, unreadCount: number): ChatChannelResponse {
  return {
    id,
    identity: { channelType: 'TEAM_PUBLIC', teamId: 1, organizationId: null },
    meta: { name: `room-${id}`, iconKey: null, description: null },
    settings: { isPrivate: false, isInquiryChannel: false, isArchived: false, version: 1 },
    lastMessage: { lastMessageAt: null, lastMessagePreview: null },
    source: { sourceType: null, sourceId: null },
    audit: { createdBy: null, createdAt: null, updatedAt: null },
    memberCount: 2,
    dmPartner: null,
    viewer: { unreadCount, isMuted: false, isPinned: false, category: null, role: null },
  } as ChatChannelResponse
}

beforeEach(() => {
  getChannels.mockReset()
  markAsRead.mockReset()
})

describe('ChatChannelList.vue（既読でバッジを消す）', () => {
  it('既読成功の通知を受けたチャンネルだけ未読バッジが消える', async () => {
    getChannels.mockResolvedValue({ data: [makeChannel(10, 4), makeChannel(11, 2)] })
    markAsRead.mockResolvedValue(undefined)
    const wrapper = await mountSuspended(ChatChannelList)
    await flushPromises()

    const badgeOf = (id: number) => wrapper.find(`[data-testid="chat-channel-${id}"] [data-testid="chat-channel-unread"]`)
    expect(badgeOf(10).exists()).toBe(true)
    expect(badgeOf(11).exists()).toBe(true)

    await useChatReadSync().markChannelRead(10)
    await flushPromises()

    expect(badgeOf(10).exists()).toBe(false)
    expect(badgeOf(11).exists()).toBe(true)
  })

  it('空状態の文言は日本語の直書きではなくロケールキー経由で描画される', async () => {
    getChannels.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(ChatChannelList)
    await flushPromises()
    expect(wrapper.find('[data-testid="chat-channel-list-no-dm"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chat-channel-list-no-room"]').exists()).toBe(true)
  })
})
