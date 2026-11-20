// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import DigestConfigForm from '~/components/timeline-digest/DigestConfigForm.vue'

const { getConfig, updateConfig } = vi.hoisted(() => ({
  getConfig: vi.fn(),
  updateConfig: vi.fn(),
}))
mockNuxtImport('useTimelineDigestApi', () => () => ({ getConfig, updateConfig }))
mockNuxtImport('useNotification', () => () => ({ error: vi.fn(), success: vi.fn() }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
const SaveButton = defineComponent({
  emits: ['click'],
  setup:
    (_, { emit }) =>
    () =>
      h('button', { onClick: () => emit('click') }, 'save'),
})

function render(scopeType: 'TEAM' | 'ORGANIZATION') {
  return mount(DigestConfigForm, {
    props: { scopeType, scopeId: 101 } as unknown as InstanceType<
      typeof DigestConfigForm
    >['$props'],
    global: {
      mocks: { $t: (key: string) => key },
      stubs: {
        Button: SaveButton,
        PageLoading: true,
        ToggleSwitch: true,
        Select: true,
        Checkbox: true,
        InputText: true,
      },
    },
  })
}

describe('CMP-260918-0024 設定フォームのスコープ', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getConfig.mockResolvedValue({
      data: {
        digestStyle: 'TEMPLATE',
        autoPublish: false,
        includeReactions: true,
        includePolls: true,
        isEnabled: false,
        scheduleTime: null,
      },
    })
    updateConfig.mockResolvedValue({ data: {} })
  })

  it.each(['TEAM', 'ORGANIZATION'] as const)(
    '%s propsの数値IDを設定GETへ渡す',
    async (scopeType) => {
      const wrapper = render(scopeType)
      await flushPromises()

      expect(getConfig).toHaveBeenCalledWith(scopeType, 101)
      wrapper.unmount()
    },
  )

  it.each(['TEAM', 'ORGANIZATION'] as const)(
    '%s propsの数値IDを保存bodyへ渡す',
    async (scopeType) => {
      const wrapper = render(scopeType)
      await flushPromises()

      await wrapper.get('button').trigger('click')
      await flushPromises()

      expect(updateConfig).toHaveBeenCalledWith(
        expect.objectContaining({ scopeType, scopeId: 101 }),
      )
      wrapper.unmount()
    },
  )
})
