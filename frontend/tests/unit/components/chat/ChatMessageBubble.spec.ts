import { describe, it, expect, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatMessageBubble from '~/components/chat/ChatMessageBubble.vue'
import type { ChatMessageResponse } from '~/types/chat'
import type { ChatContextMenuItem } from '~/components/chat/ChatContextMenu.vue'

/**
 * CMP-261105-1507 欠陥2: 自分の投稿を編集・削除できない導線欠落の回帰テスト。
 *
 * - 本人（isMine）には「⋯」メニューに編集・削除、ホバーにゴミ箱が出る
 * - 本人でも管理者でもない利用者には編集・削除が出ない
 * - 管理者（canDelete）には他人の投稿の削除だけが出る（編集は出ない）
 * - メニューの「編集」選択で edit イベントが発火する（従来は選んでも何も起きなかった）
 */

vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 }, isAuthenticated: false, loadFromStorage: vi.fn() }),
}))

function makeMessage(overrides: Partial<ChatMessageResponse> = {}): ChatMessageResponse {
  return {
    id: 501,
    channelId: 10,
    sender: { id: 42, displayName: '本人' },
    parentId: null,
    body: 'こんにちは',
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
    isDeleted: false,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    rootId: null,
    depth: 0,
    suggestBoardMigration: false,
    messageType: 'TEXT',
    inviteData: null,
    ...overrides,
  }
}

async function menuKeys(props: Record<string, unknown>): Promise<string[]> {
  const wrapper = await mountSuspended(ChatMessageBubble, { props: { message: makeMessage(), ...props } })
  const menu = wrapper.findComponent({ name: 'ChatContextMenu' })
  return (menu.props('items') as ChatContextMenuItem[]).map((i) => i.key)
}

describe('ChatMessageBubble.vue（本人・管理者の操作出し分け）', () => {
  it('本人には編集と削除がメニューに出る', async () => {
    const keys = await menuKeys({ isMine: true })
    expect(keys).toContain('edit')
    expect(keys).toContain('delete')
  })

  it('本人でも管理者でもない利用者には編集も削除も出ない', async () => {
    const keys = await menuKeys({ isMine: false, canDelete: false })
    expect(keys).not.toContain('edit')
    expect(keys).not.toContain('delete')
  })

  it('管理者には他人の投稿の削除だけが出る（編集は出ない）', async () => {
    const keys = await menuKeys({ isMine: false, canDelete: true })
    expect(keys).toContain('delete')
    expect(keys).not.toContain('edit')
  })

  it('本人にはホバーのゴミ箱が出る（管理者フラグに依存しない）', async () => {
    const wrapper = await mountSuspended(ChatMessageBubble, {
      props: { message: makeMessage(), isMine: true, canDelete: false },
    })
    await wrapper.trigger('mouseenter')
    expect(wrapper.find('[data-testid="chat-message-delete"]').exists()).toBe(true)
  })

  it('本人でも管理者でもなければホバーのゴミ箱は出ない', async () => {
    const wrapper = await mountSuspended(ChatMessageBubble, {
      props: { message: makeMessage(), isMine: false, canDelete: false },
    })
    await wrapper.trigger('mouseenter')
    expect(wrapper.find('[data-testid="chat-message-delete"]').exists()).toBe(false)
  })

  it('メニューの「編集」選択で edit イベントが発火する', async () => {
    const wrapper = await mountSuspended(ChatMessageBubble, {
      props: { message: makeMessage(), isMine: true },
    })
    const menu = wrapper.findComponent({ name: 'ChatContextMenu' })
    menu.vm.$emit('select', 'edit')
    expect(wrapper.emitted('edit')?.[0]).toEqual([501])
  })

  it('削除済みメッセージの表示文言はロケールキーから引く（直書きしない）', async () => {
    const wrapper = await mountSuspended(ChatMessageBubble, {
      props: { message: makeMessage({ isDeleted: true, body: null, sender: null }) },
    })
    expect(wrapper.find('[data-testid="chat-message-deleted"]').exists()).toBe(true)
  })
})
