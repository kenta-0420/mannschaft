/**
 * V1（CMP-261110-0426）: 削除済みチームへの招待カードでチーム名が空にならない。
 * scopeName が null / 空文字 / 空白のみでも、フォールバック文言（i18n）を見出しと本文に出す。
 */
import { defineComponent } from 'vue'
import { describe, expect, it } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ChatInviteCard from '~/components/chat/ChatInviteCard.vue'

mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) =>
    params?.scope !== undefined ? `${key}[${String(params.scope)}]` : key,
}))
mockNuxtImport('useRelativeTime', () => () => ({ relativeTime: () => 'in 7 days' }))

const ButtonStub = defineComponent({
  props: { label: { type: String, default: '' } },
  template: '<button type="button">{{ label }}</button>',
})

function baseInvite(scopeName: unknown) {
  return {
    tokenId: 1,
    scopeType: 'TEAM' as const,
    scopeId: 10,
    scopeName,
    status: 'PENDING' as const,
    isTarget: true,
    expiresAt: '2026-11-14T00:00:00Z',
  }
}

async function mountCard(scopeName: unknown) {
  return mountSuspended(ChatInviteCard, {
    props: { invite: baseInvite(scopeName) as never },
    global: { stubs: { Button: ButtonStub }, mocks: { $t: (key: string) => key } },
  })
}

describe('ChatInviteCard 削除済みスコープ', () => {
  it.each([null, undefined, '', '   '])('scopeName=%j でもフォールバック名を出す', async (name) => {
    const wrapper = await mountCard(name)
    const text = wrapper.text()
    expect(text).toContain('chat.invite.card.scopeDeleted')
    expect(text).toContain('chat.invite.card.pending[chat.invite.card.scopeDeleted]')
  })

  it('通常の scopeName はそのまま出す（回帰）', async () => {
    const wrapper = await mountCard('FC Mannschaft')
    expect(wrapper.text()).toContain('FC Mannschaft')
    expect(wrapper.text()).not.toContain('chat.invite.card.scopeDeleted')
  })
})
