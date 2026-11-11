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

/**
 * CMP-261030-0648 AC-24: 問い合わせ通知の遷移先 /teams/{slug}/chat?channel={id} から開いたとき、
 * そのチャンネルが選択された状態で開く。一覧に無い（存在しない・見られない）なら未選択のまま、エラーも出さない。
 */
describe('ChatChannelList.vue（initialChannelId で開いた直後に選択する）', () => {
  it('AC-24: 一覧に含まれる initialChannelId のチャンネルを選択して select を発火する', async () => {
    getChannels.mockResolvedValue({ data: [makeChannel(10, 0), makeChannel(11, 0)] })
    const wrapper = await mountSuspended(ChatChannelList, { props: { initialChannelId: 11 } })
    await flushPromises()

    const selected = wrapper.emitted('select')
    expect(selected).toHaveLength(1)
    expect((selected![0]![0] as ChatChannelResponse).id).toBe(11)
  })

  it('AC-24: 一覧に無い initialChannelId なら未選択のまま（select を発火しない・一覧は通常どおり出る）', async () => {
    getChannels.mockResolvedValue({ data: [makeChannel(10, 0)] })
    const wrapper = await mountSuspended(ChatChannelList, { props: { initialChannelId: 999 } })
    await flushPromises()

    expect(wrapper.emitted('select')).toBeUndefined()
    expect(wrapper.emitted('loaded')?.[0]).toEqual([1])
    expect(wrapper.find('[data-testid="chat-channel-10"]').exists()).toBe(true)
  })

  it('AC-24: initialChannelId が無ければ自動では選択しない（従来どおり）', async () => {
    getChannels.mockResolvedValue({ data: [makeChannel(10, 0)] })
    const wrapper = await mountSuspended(ChatChannelList)
    await flushPromises()

    expect(wrapper.emitted('select')).toBeUndefined()
  })
})
