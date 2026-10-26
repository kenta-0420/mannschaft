import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import PublicVisibleToggle from '~/components/admin/PublicVisibleToggle.vue'

const patchPublicVisible = vi.fn()

mockNuxtImport('useBlogApi', () => () => ({ patchPublicVisible }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

const stubs = {
  InputSwitch: {
    props: ['modelValue', 'disabled', 'ariaLabel', 'inputId'],
    emits: ['update:modelValue'],
    template: '<button type="button" :disabled="disabled" :aria-label="ariaLabel" @click="$emit(\'update:modelValue\', !modelValue)">{{ modelValue }}</button>',
  },
  LoadingBounce: { template: '<span data-test="loading" />' },
}

async function mountToggle(publicVisible = true) {
  return mountSuspended(PublicVisibleToggle, {
    props: { postId: 42, publicVisible },
    global: { stubs },
  })
}

describe('PublicVisibleToggle', () => {
  beforeEach(() => {
    patchPublicVisible.mockReset()
    patchPublicVisible.mockResolvedValue(undefined)
  })

  it('API成功後に変更値を通知する', async () => {
    const wrapper = await mountToggle(true)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(patchPublicVisible).toHaveBeenCalledWith(42, false)
    expect(wrapper.emitted('update:publicVisible')).toEqual([[false]])
    expect(wrapper.emitted('error')).toBeUndefined()
  })

  it('API失敗時は元の値へ戻してエラーを通知する', async () => {
    patchPublicVisible.mockRejectedValue(new Error('network'))
    const wrapper = await mountToggle(true)
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.get('button').text()).toBe('true')
    expect(wrapper.emitted('update:publicVisible')).toBeUndefined()
    expect(wrapper.emitted('error')).toEqual([['public.admin.publicVisible.saveFailed']])
    expect(wrapper.get('[role="alert"]').text()).toBe('public.admin.publicVisible.saveFailed')
  })

  it('通信中は入力を無効化して二重送信を防ぐ', async () => {
    let resolveRequest!: () => void
    patchPublicVisible.mockImplementation(() => new Promise<void>((resolve) => {
      resolveRequest = resolve
    }))
    const wrapper = await mountToggle(true)
    await wrapper.get('button').trigger('click')
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    await wrapper.get('button').trigger('click')
    expect(patchPublicVisible).toHaveBeenCalledTimes(1)
    resolveRequest()
    await flushPromises()
  })
})
