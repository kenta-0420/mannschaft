import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import ContactSearchDialog from '~/components/contact/ContactSearchDialog.vue'

/**
 * CMP-261105-1507: ハンドル検索は、見つからないとき 200 で { found:false, fullName:null, ... } を返す。
 * これを「結果あり」として描画すると fullName.charAt で TypeError になりダイアログが崩れていた。
 * found:false は「見つかりませんでした」（ロケール文言）として案内する。
 */

const searchByHandle = vi.fn()
mockNuxtImport('useContactApi', () => () => ({ searchByHandle, sendRequest: vi.fn() }))

const DialogStub = defineComponent({
  name: 'Dialog',
  props: ['visible', 'header'],
  setup(_p, { slots }) {
    return () => h('div', { 'data-testid': 'dialog' }, slots.default?.())
  },
})

beforeEach(() => {
  vi.useFakeTimers()
  searchByHandle.mockReset()
})
afterEach(() => {
  vi.useRealTimers()
})

async function typeHandle(value: string) {
  const wrapper = await mountSuspended(ContactSearchDialog, {
    props: { visible: true },
    global: { stubs: { Dialog: DialogStub } },
  })
  await wrapper.find('input').setValue(value)
  await vi.advanceTimersByTimeAsync(600)
  await flushPromises()
  return wrapper
}

describe('ContactSearchDialog.vue（ハンドル検索）', () => {
  it('found:false（fullName=null）は「見つかりません」の案内（ロケール文言）を出し、結果カードを描画しない', async () => {
    searchByHandle.mockResolvedValue({
      data: { found: false, userId: null, fullName: null, contactHandle: null, avatarUrl: null },
    })
    const wrapper = await typeHandle('nobody_here')

    const notFoundText = useNuxtApp().$i18n.t('contact_handle_search.not_found')
    expect(notFoundText).not.toBe('contact_handle_search.not_found') // キーが解決されている
    expect(wrapper.text()).toContain(notFoundText)
    expect(wrapper.text()).not.toContain('連絡先に追加')
  })

  it('found:true は結果カードを描画する', async () => {
    searchByHandle.mockResolvedValue({
      data: {
        found: true,
        userId: 5,
        fullName: '山田太郎',
        contactHandle: 'taro',
        avatarUrl: null,
        isContact: false,
        hasPendingRequest: false,
        contactApprovalRequired: false,
      },
    })
    const wrapper = await typeHandle('taro')

    expect(wrapper.text()).toContain('山田太郎')
    expect(wrapper.text()).not.toContain(useNuxtApp().$i18n.t('contact_handle_search.not_found'))
  })
})
