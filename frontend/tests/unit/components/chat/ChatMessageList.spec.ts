import { describe, it, expect, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatMessageList from '~/components/chat/message-panel/ChatMessageList.vue'
import ChatMessageBubble from '~/components/chat/ChatMessageBubble.vue'
import type { ChatMessageResponse } from '~/types/chat'

/**
 * CMP-261105-1507 欠陥2: 一覧が各メッセージに「本人の投稿か」を渡していなかったため、
 * 本人にも編集・削除が出なかった。送信者 ID とログイン中ユーザー ID の一致で isMine を渡すことを固定する。
 */

vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 }, isAuthenticated: false, loadFromStorage: vi.fn() }),
}))

function makeMessage(id: number, senderId: number | null): ChatMessageResponse {
  return {
    id,
    channelId: 10,
    sender: senderId === null ? null : { id: senderId, displayName: `user-${senderId}` },
    parentId: null,
    body: 'body',
    isEdited: false,
    isSystem: false,
    isPinned: false,
    replyCount: 0,
    reactionCount: 0,
    reactionSummary: {},
    myReactions: [],
    attachments: [],
    isBookmarked: false,
    forwardedFrom: null,
    isDeleted: senderId === null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    rootId: null,
    depth: 0,
    suggestBoardMigration: false,
    messageType: 'TEXT',
    inviteData: null,
  }
}

describe('ChatMessageList.vue（isMine の受け渡し）', () => {
  it('ログイン中ユーザーの投稿にだけ isMine=true を渡す', async () => {
    const wrapper = await mountSuspended(ChatMessageList, {
      props: {
        messages: [makeMessage(1, 42), makeMessage(2, 7), makeMessage(3, null)],
        loading: false,
        hasMore: false,
        nextCursor: null,
      },
    })
    const bubbles = wrapper.findAllComponents(ChatMessageBubble)
    expect(bubbles).toHaveLength(3)
    expect(bubbles.map((b) => b.props('isMine'))).toEqual([true, false, false])
  })

  it('子の edit イベントを親へ中継する', async () => {
    const wrapper = await mountSuspended(ChatMessageList, {
      props: { messages: [makeMessage(1, 42)], loading: false, hasMore: false, nextCursor: null },
    })
    wrapper.findComponent(ChatMessageBubble).vm.$emit('edit', 1)
    expect(wrapper.emitted('edit')?.[0]).toEqual([1])
  })
})
