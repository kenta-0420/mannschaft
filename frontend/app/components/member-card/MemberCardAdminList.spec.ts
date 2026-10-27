import { describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import MemberCardAdminList from './MemberCardAdminList.vue'
import type { MemberCardListItem } from '~/types/member-card'

const qrApi = vi.hoisted(() => vi.fn())
mockNuxtImport('useMemberCardApi', () => qrApi)

describe('MemberCardAdminList', () => {
  it('氏名と番号を表示し履歴・停止・再開を通知するが、本人QRを取得しない', async () => {
    const cards: MemberCardListItem[] = ['ACTIVE', 'SUSPENDED', 'REVOKED'].map((status, i) => ({
      id: i + 1, userId: i + 10, cardNumber: `M-${i}`, displayName: `会員${i}`,
      status: status as MemberCardListItem['status'], issuedAt: '2026-10-01T12:00:00',
      lastCheckinAt: null, checkinCount: i,
    }))
    const wrapper = await mountSuspended(MemberCardAdminList, {
      props: { cards, busy: false },
      global: { stubs: {
        SectionCard: { props: ['title'], template: '<section><h2>{{ title }}</h2><slot /></section>' },
        Button: { props: ['label'], emits: ['click'], template: '<button @click="$emit(\'click\')">{{ label }}</button>' },
        SelectButton: true,
      } },
    })
    expect(wrapper.text()).toContain('会員0')
    expect(wrapper.text()).toContain('M-1')
    const buttons = wrapper.findAll('button')
    expect(buttons).toHaveLength(5)
    await buttons[0]!.trigger('click')
    await buttons[1]!.trigger('click')
    await buttons[3]!.trigger('click')
    expect(wrapper.emitted('select')).toEqual([[cards[0]]])
    expect(wrapper.emitted('suspend')).toEqual([[1]])
    expect(wrapper.emitted('reactivate')).toEqual([[2]])
    expect(qrApi).not.toHaveBeenCalled()
    expect(wrapper.find('img').exists()).toBe(false)
  })
})
