// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { flushPromises, mount } from '@vue/test-utils'
import MemberCardDisplay from '~/components/member-card/MemberCardDisplay.vue'

/** CMP-261112-2225 追加修正: 会員証画面の伝播・二重送信・折り返し・初期選択の番人 */
const getQr = vi.hoisted(() => vi.fn())
const regenerate = vi.hoisted(() => vi.fn())
vi.mock('~/composables/useMemberCardApi', () => ({ useMemberCardApi: () => ({ getQr, regenerate }) }))
vi.stubGlobal('useMemberCardApi', () => ({ getQr, regenerate }))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => ({ error: vi.fn(), success: vi.fn() }) }))
vi.stubGlobal('useNotification', () => ({ error: vi.fn(), success: vi.fn() }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))

const card = { id: 9, userId: 1, scopeType: 'PLATFORM', scopeId: null, scopeName: null, cardCode: 'c', cardNumber: 'n', status: 'ACTIVE', checkinCount: 0, lastCheckinAt: null, createdAt: '' }
const ButtonStub = {
  props: ['label', 'disabled', 'loading'],
  emits: ['click'],
  template: '<button :data-label="label" :disabled="disabled || loading" @click="$emit(\'click\', $event)">{{ label }}</button>',
}
function build(parentClick: () => void) {
  getQr.mockResolvedValue({ memberCardId: 9, qrToken: 't', expiresIn: 300, cardNumber: 'n', displayName: 'M', scopeType: 'PLATFORM', scopeName: null })
  return mount(
    { components: { MemberCardDisplay }, template: '<div @click="onClick"><MemberCardDisplay :card="card" /></div>', data: () => ({ card }), methods: { onClick: parentClick } },
    { global: { mocks: { $t: (k: string) => k }, stubs: { Card: { template: '<div><slot name="content" /><slot name="footer" /></div>' }, Button: ButtonStub, Badge: true, QrCodeImage: true, LoadingBounce: true } } },
  )
}
const read = (p: string) => readFileSync(resolve(__dirname, '../../../app', p), 'utf-8')

describe('MemberCardDisplay ボタン', () => {
  it('ボタンのクリックが外枠へ伝播しない', async () => {
    const onParent = vi.fn()
    const w = build(onParent)
    await flushPromises()
    regenerate.mockResolvedValue({ qrToken: 't2' })
    for (const b of w.findAll('button')) await b.trigger('click')
    await flushPromises()
    expect(onParent).not.toHaveBeenCalled()
  })

  it('QR再生成中は再度押せず、POSTは1本だけ', async () => {
    const w = build(vi.fn())
    await flushPromises()
    let done!: (v: unknown) => void
    regenerate.mockReset()
    regenerate.mockReturnValue(new Promise((r) => { done = r }))
    const btn = w.find('button[data-label="memberCardDisplay.regenerate"]')
    await btn.trigger('click')
    await btn.trigger('click')
    expect(regenerate).toHaveBeenCalledTimes(1)
    expect(btn.attributes('disabled')).toBeDefined()
    done({ qrToken: 'x' })
    await flushPromises()
    expect(btn.attributes('disabled')).toBeUndefined()
  })
})

describe('折り返し・初期選択の構造番人', () => {
  it('ステータスフィルタ行とボタン列が折り返し可能', () => {
    expect(read('components/member-card/MemberCardList.vue')).toMatch(/mb-4 flex flex-wrap items-center/)
    expect(read('components/member-card/MemberCardDisplay.vue')).toMatch(/flex flex-wrap justify-center gap-2/)
  })
  it('一覧は選択中カードを示し、ページは先頭カードを初期選択する', () => {
    expect(read('components/member-card/MemberCardList.vue')).toMatch(/selectedId/)
    expect(read('pages/settings/member-cards.vue')).toMatch(/cards\.value\[0\]/)
  })
})
