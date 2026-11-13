// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'
import { h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import MemberCardDisplay from './MemberCardDisplay.vue'
import QrCodeImage from '../qr/QrCodeImage.vue'

const getQr = vi.hoisted(() => vi.fn())
const regenerate = vi.hoisted(() => vi.fn())
vi.mock('~/composables/useMemberCardApi', () => ({ useMemberCardApi: () => ({ getQr, regenerate }) }))
vi.stubGlobal('useMemberCardApi', () => ({ getQr, regenerate }))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => ({ error: vi.fn(), success: vi.fn() }) }))
vi.stubGlobal('useNotification', () => ({ error: vi.fn(), success: vi.fn() }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))

describe('会員QRのローカル描画（AC01/10）', () => {
  it('正確DTOのqrTokenをローカルSVGへ変換し、第三者へ秘密を送信しない', async () => {
    getQr.mockResolvedValue({ memberCardId: 9, qrToken: 'private-member-qr', expiresIn: 300, cardNumber: 'card', displayName: 'Member', scopeType: 'PLATFORM', scopeName: null })
    const fetch = vi.spyOn(globalThis, 'fetch')
    const wrapper = mount(MemberCardDisplay, {
      props: { card: { id: 9, userId: 1, scopeType: 'PLATFORM', scopeId: null, scopeName: null, cardCode: 'code', cardNumber: 'card', status: 'ACTIVE', checkinCount: 0, lastCheckinAt: null, createdAt: '' } },
      global: { mocks: { $t: (key: string) => key }, components: { QrCodeImage }, stubs: { Card: { template: '<div><slot name="header" /><slot name="content" /><slot name="footer" /></div>' }, Button: true, Badge: true } },
    })
    await flushPromises()
    expect(wrapper.getComponent(QrCodeImage).props('value')).toBe('private-member-qr')
    expect(wrapper.find('svg').exists()).toBe(true)
    expect(wrapper.find('img[src*="qrserver"]').exists()).toBe(false)
    expect(fetch).not.toHaveBeenCalled()
    wrapper.unmount(); fetch.mockRestore()
  })

  it('QR再生成後は応答（qrTokenなし）ではなくGET /qrを再取得して新しいQRを表示する（CMP-261112-2225）', async () => {
    getQr.mockReset()
    getQr
      .mockResolvedValueOnce({ memberCardId: 9, qrToken: 'old-token', expiresIn: 300, cardNumber: 'card', displayName: 'Member', scopeType: 'PLATFORM', scopeName: null })
      .mockResolvedValueOnce({ memberCardId: 9, qrToken: 'new-token', expiresIn: 300, cardNumber: 'card', displayName: 'Member', scopeType: 'PLATFORM', scopeName: null })
    regenerate.mockResolvedValue({ memberCardId: 9, message: 'ok', regeneratedAt: '2026-11-13T00:00:00' })
    const wrapper = mount(MemberCardDisplay, {
      props: { card: { id: 9, userId: 1, scopeType: 'PLATFORM', scopeId: null, scopeName: null, cardCode: 'code', cardNumber: 'card', status: 'ACTIVE', checkinCount: 0, lastCheckinAt: null, createdAt: '' } },
      global: { mocks: { $t: (key: string) => key }, components: { QrCodeImage }, stubs: { Card: { template: '<div><slot name="header" /><slot name="content" /><slot name="footer" /></div>' }, Button: { emits: ['click'], setup: (_p: unknown, { emit }: { emit: (e: 'click', ev: Event) => void }) => () => h('button', { class: 'regen', onClick: (ev: Event) => emit('click', ev) }) }, Badge: true } },
    })
    await flushPromises()
    expect(wrapper.getComponent(QrCodeImage).props('value')).toBe('old-token')
    await wrapper.find('button.regen').trigger('click')
    await flushPromises()
    expect(regenerate).toHaveBeenCalledWith(9)
    expect(getQr).toHaveBeenCalledTimes(2)
    expect(wrapper.getComponent(QrCodeImage).props('value')).toBe('new-token')
    wrapper.unmount()
  })
})
