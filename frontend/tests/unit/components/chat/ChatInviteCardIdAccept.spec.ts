/**
 * 試練（CMP-260826-1457 W1 P1・AC-12/AC-18）: 承諾型招待カードは平文 token なしで、招待 ID（tokenId）で承諾・辞退する。
 *
 * 御裁可 §6-2: 宛先指定型の招待は invitationId と認証済み本人の照合で承諾・辞退する。カードの inviteData から
 * token を除き、ボタンは tokenId を emit する。
 *
 * 実装側が満たすべき契約:
 * - `ChatInviteData`（FE）に `token` を持たない。カードは `tokenId` だけで動く。
 * - 宛先本人・PENDING のとき、参加（data-testid="chat-invite-join"）で `join` を、辞退（"chat-invite-decline"）で
 *   `decline` を、それぞれ `tokenId`（number）を引数に emit する。
 * - `mapBeMessage` は BE の inviteData に（旧 BE が）token を含めていても、FE の inviteData へ写さない。
 *
 * red の理由: いまのカードは `invite.token` が無いと emit せず、マッパーは token を写す。
 */
import { defineComponent } from 'vue'
import { describe, expect, it } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ChatInviteCard from '~/components/chat/ChatInviteCard.vue'
import { mapBeMessage, type BeMessageResponse } from '~/composables/chat/chatMessageMapper'

mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useRelativeTime', () => () => ({ relativeTime: () => 'in 7 days' }))

const ButtonStub = defineComponent({
  props: { label: { type: String, default: '' } },
  emits: ['click'],
  template: '<button type="button" @click="$emit(\'click\')">{{ label }}</button>',
})

/** 平文 token を持たない、宛先本人視点の PENDING カード。 */
const INVITE_WITHOUT_TOKEN = {
  tokenId: 4242,
  scopeType: 'TEAM' as const,
  scopeId: 10,
  scopeName: 'FC Mannschaft',
  status: 'PENDING' as const,
  isTarget: true,
  expiresAt: '2026-11-14T00:00:00Z',
}

async function mountCard() {
  return mountSuspended(ChatInviteCard, {
    props: { invite: INVITE_WITHOUT_TOKEN as never },
    global: {
      stubs: { Button: ButtonStub },
      mocks: { $t: (key: string) => key },
    },
  })
}

describe('ChatInviteCard: ID で承諾・辞退する（CMP-260826-1457 W1 P1）', () => {
  it('参加ボタンは tokenId を emit する（平文 token が無くても動く）', async () => {
    const wrapper = await mountCard()
    const join = wrapper.find('[data-testid="chat-invite-join"]')
    expect(join.exists(), '宛先本人の PENDING カードに参加ボタンがない').toBe(true)
    await join.trigger('click')

    expect(wrapper.emitted('join')).toEqual([[4242]])
  })

  it('辞退ボタンは tokenId を emit する', async () => {
    const wrapper = await mountCard()
    const decline = wrapper.find('[data-testid="chat-invite-decline"]')
    expect(decline.exists(), '宛先本人の PENDING カードに辞退ボタンがない').toBe(true)
    await decline.trigger('click')

    expect(wrapper.emitted('decline')).toEqual([[4242]])
  })
})

describe('mapBeMessage: 招待カードに平文 token を写さない（CMP-260826-1457 W1 P1）', () => {
  it('BE の inviteData に token が混じっていても、FE の inviteData は token を持たない', () => {
    const raw = {
      id: 1,
      channelId: 10,
      senderId: 99,
      messageType: 'INVITE_CARD',
      inviteData: { ...INVITE_WITHOUT_TOKEN, token: 'legacy-plaintext-must-not-flow' },
      thread: { parentId: null, rootId: null, depth: 0, suggestBoardMigration: false },
      content: { body: '', forwardedFromId: null, isEdited: false, isSystem: false, scheduledAt: null },
      engagement: { replyCount: 0, reactionCount: 0, isPinned: false, attachments: [], reactions: [] },
      audit: { createdAt: '2026-11-07T00:00:00Z', updatedAt: '2026-11-07T00:00:00Z' },
    } as unknown as BeMessageResponse

    const mapped = mapBeMessage(raw)
    expect(mapped.inviteData).not.toBeNull()
    expect(mapped.inviteData?.tokenId).toBe(4242)
    expect(Object.keys(mapped.inviteData ?? {})).not.toContain('token')
    expect(JSON.stringify(mapped)).not.toContain('legacy-plaintext-must-not-flow')
  })
})
