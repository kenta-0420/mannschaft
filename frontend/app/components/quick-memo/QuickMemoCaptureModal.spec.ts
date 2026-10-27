import { describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import QuickMemoCaptureModal from './QuickMemoCaptureModal.vue'

const listTags = vi.fn().mockResolvedValue({ data: [{ id: 1, name: '個人タグ' }] })
mockNuxtImport('useTagApi', () => () => ({ listTags }))
mockNuxtImport('useQuickMemoApi', () => () => ({ createMemo: vi.fn() }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))

describe('QuickMemoCaptureModal 遅延初回表示', () => {
  it('初回から開いていてもタグを一度取得し、再表示では取得済みタグを保持する', async () => {
    listTags.mockClear()
    const wrapper = await mountSuspended(QuickMemoCaptureModal, {
      props: { visible: true },
      global: { stubs: { Dialog: { template: '<div><slot /></div>' }, InputText: true, QuickMemoVoiceInput: true } },
    })
    await flushPromises()
    expect(listTags).toHaveBeenCalledExactlyOnceWith('personal')
    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true })
    await flushPromises()
    expect(listTags).toHaveBeenCalledOnce()
  })
})
