// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import CheckinLocationManager from './CheckinLocationManager.vue'
import en from '~/locales/en/member-card.json'
import PrimeVue from 'primevue/config'
import { createI18n } from 'vue-i18n'
import common from '~/locales/en/common.json'

const mocks = vi.hoisted(() => ({
  listLocations: vi.fn(),
  createLocation: vi.fn(),
  updateLocation: vi.fn(),
  deleteLocation: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
}))
mockNuxtImport('useMemberCardApi', () => () => mocks)
mockNuxtImport('useNotification', () => () => mocks)
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.error }))

async function mountManager() {
  const wrapper = mount(CheckinLocationManager, {
    props: { teamId: 'test-team' },
    global: {
      plugins: [
        PrimeVue,
        createI18n({ legacy: false, locale: 'en', messages: { en: { ...common, ...en } } }),
      ],
      stubs: {
        DataTable: {
          props: ['value'],
          template: '<div><slot /><slot v-if="!value.length" name="empty" /></div>',
        },
        Column: { props: ['header'], template: '<span>{{ header }}</span>' },
        Dialog: {
          props: ['visible', 'header'],
          template:
            '<section v-if="visible"><h2>{{ header }}</h2><slot /><slot name="footer" /></section>',
        },
        Button: {
          props: ['label'],
          emits: ['click'],
          template: '<button @click="$emit(\'click\')">{{ label }}</button>',
        },
      },
    },
  })
  await flushPromises()
  return wrapper
}

describe('チェックイン拠点の管理表示', () => {
  beforeEach(() => vi.clearAllMocks())
  it('英語で見出し、空状態、列、作成ダイアログを表示する', async () => {
    mocks.listLocations.mockResolvedValue([])
    const wrapper = await mountManager()
    expect(wrapper.text()).toContain('Self-check-in locations')
    expect(wrapper.text()).toContain('No locations registered')
    const add = wrapper.findAll('button').find((b) => b.text() === 'Add location')!
    await add.trigger('click')
    expect(wrapper.text()).toContain('Location name')
    expect(wrapper.text()).toContain('Automatically complete reservations at check-in')
    expect(wrapper.text()).not.toMatch(/[ぁ-んァ-ヶ一-龯]/)
  })

  it('取得失敗を空状態にせず、同じ取得処理で再試行する', async () => {
    mocks.listLocations.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce([])
    const wrapper = await mountManager()
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('No locations registered')
    await wrapper.get('[data-testid="load-error-state-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('No locations registered')
  })

  it('作成操作の成功通知も英語で表示する', async () => {
    mocks.listLocations.mockResolvedValue([])
    mocks.createLocation.mockResolvedValue({})
    const wrapper = await mountManager()
    await wrapper
      .findAll('button')
      .find((b) => b.text() === 'Add location')!
      .trigger('click')
    await wrapper.get('input').setValue('Reception')
    await wrapper
      .findAll('button')
      .find((b) => b.text() === 'Create')!
      .trigger('click')
    await flushPromises()
    expect(mocks.createLocation).toHaveBeenCalledWith('test-team', {
      name: 'Reception',
      autoCompleteReservation: false,
    })
    expect(mocks.success).toHaveBeenCalledWith('Location created')
  })
})
