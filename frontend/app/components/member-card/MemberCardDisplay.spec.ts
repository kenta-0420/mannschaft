import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import MemberCardDisplay from './MemberCardDisplay.vue'
import QrCodeImage from '../qr/QrCodeImage.vue'

const getQr = vi.hoisted(() => vi.fn())
vi.mock('~/composables/useMemberCardApi', () => ({ useMemberCardApi: () => ({ getQr }) }))
vi.stubGlobal('useMemberCardApi', () => ({ getQr }))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => ({ error: vi.fn() }) }))
vi.stubGlobal('useNotification', () => ({ error: vi.fn() }))

describe('会員QRのローカル描画（AC01/10）', () => {
  it('正確DTOのqrTokenをローカルSVGへ変換し、第三者へ秘密を送信しない', async () => {
    getQr.mockResolvedValue({ memberCardId: 9, qrToken: 'private-member-qr', expiresIn: 300, cardNumber: 'card', displayName: 'Member', scopeType: 'PLATFORM', scopeName: null })
    const fetch = vi.spyOn(globalThis, 'fetch')
    const wrapper = mount(MemberCardDisplay, {
      props: { card: { id: 9, userId: 1, scopeType: 'PLATFORM', scopeId: null, scopeName: null, cardCode: 'code', cardNumber: 'card', status: 'ACTIVE', checkinCount: 0, lastCheckinAt: null, createdAt: '' } },
      global: { components: { QrCodeImage }, stubs: { Card: { template: '<div><slot name="header" /><slot name="content" /><slot name="footer" /></div>' }, Button: true, Badge: true } },
    })
    await flushPromises()
    expect(wrapper.getComponent(QrCodeImage).props('value')).toBe('private-member-qr')
    expect(wrapper.find('svg').exists()).toBe(true)
    expect(wrapper.find('img[src*="qrserver"]').exists()).toBe(false)
    expect(fetch).not.toHaveBeenCalled()
    wrapper.unmount(); fetch.mockRestore()
  })
})
