import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import { useChatReadSync } from '~/composables/chat/useChatReadSync'
import { useChatTabsStore } from '~/stores/useChatTabsStore'
import type { ChatChannelResponse } from '~/types/chat'

/**
 * CMP-261105-1507 欠陥3: 既読に成功しても未読バッジが消えなかった回帰テスト。
 *
 * - 既読 API の完了を待ってから、一覧・タブへ「既読になった」ことを伝える
 * - 失敗は呼び出し側へ例外として返す（握りつぶさない）。失敗時は既読扱いにしない
 */

const markAsRead = vi.fn()
mockNuxtImport('useChatApi', () => () => ({ markAsRead }))

function makeChannel(id: number, unreadCount: number): ChatChannelResponse {
  return {
    id,
    identity: { channelType: 'DM', teamId: null, organizationId: null },
    meta: { name: null, iconKey: null, description: null },
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
  setActivePinia(createPinia())
  markAsRead.mockReset()
})

describe('useChatReadSync', () => {
  it('既読 API 成功後に購読者へチャンネル ID を通知する', async () => {
    markAsRead.mockResolvedValue(undefined)
    const { markChannelRead, onChannelRead } = useChatReadSync()
    const received: number[] = []
    const off = onChannelRead((id) => received.push(id))
    await markChannelRead(10)
    off()
    expect(markAsRead).toHaveBeenCalledWith(10)
    expect(received).toEqual([10])
  })

  it('既読 API 成功後に同じチャンネルのタブの未読数を 0 にする', async () => {
    markAsRead.mockResolvedValue(undefined)
    const tabs = useChatTabsStore()
    tabs.addTab(makeChannel(10, 5))
    tabs.addTab(makeChannel(11, 3))
    const { markChannelRead } = useChatReadSync()
    await markChannelRead(10)
    expect(tabs.tabs.find((t) => t.channelId === 10)?.channel.viewer?.unreadCount).toBe(0)
    expect(tabs.tabs.find((t) => t.channelId === 11)?.channel.viewer?.unreadCount).toBe(3)
  })

  it('既読 API 失敗は例外として返し、既読を通知しない', async () => {
    markAsRead.mockRejectedValue(new Error('boom'))
    const { markChannelRead, onChannelRead } = useChatReadSync()
    const received: number[] = []
    const off = onChannelRead((id) => received.push(id))
    await expect(markChannelRead(10)).rejects.toThrow('boom')
    off()
    expect(received).toEqual([])
  })
})
