// @vitest-environment happy-dom

import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import DashboardErrorState from '~/components/DashboardErrorState.vue'
import enMessages from '~/locales/en/common.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: { ja: {}, en: enMessages, zh: {}, ko: {}, es: {}, de: {} },
})

const ButtonStub = {
  props: ['label'],
  emits: ['click'],
  template:
    '<button type="button" v-bind="$attrs" @click="$emit(\'click\')">{{ label }}</button>',
}

function mountErrorState(props: Record<string, unknown> = {}) {
  return mount(DashboardErrorState, {
    props,
    global: {
      plugins: [i18n],
      stubs: { Button: ButtonStub },
    },
  })
}

describe('DashboardErrorState.vue', () => {
  it('既定では一時的な取得失敗をやわらかく案内して再試行を表示する', () => {
    const wrapper = mountErrorState()

    expect(wrapper.text()).toContain("We can't load the data right now")
    expect(wrapper.text()).toContain('This may be a temporary issue')
    expect(wrapper.find('[data-testid="load-error-state-retry"]').exists()).toBe(true)
  })

  it.each([
    [{ statusCode: 403 }, "This page isn't available for this account"],
    [{ response: { status: 404 } }, "We couldn't display what you're looking for"],
  ])('403/404 は安全な案内へ分けて再試行を表示しない', (error, expectedTitle) => {
    const wrapper = mountErrorState({ error, testid: 'classified-error' })

    expect(wrapper.text()).toContain(expectedTitle)
    expect(wrapper.find('[data-testid="classified-error-retry"]').exists()).toBe(false)
  })

  it('通信断は接続確認を案内して再試行を表示する', () => {
    const wrapper = mountErrorState({
      error: new TypeError('Failed to fetch'),
      testid: 'network-error',
    })

    expect(wrapper.text()).toContain("It looks like the connection didn't work")
    expect(wrapper.find('[data-testid="network-error-retry"]').exists()).toBe(true)
  })

  it('個別文言と再試行表示の明示指定を優先する', async () => {
    const wrapper = mountErrorState({
      error: { statusCode: 403 },
      title: 'Custom title',
      message: 'Custom message',
      showRetry: true,
      testid: 'custom-error',
    })

    expect(wrapper.text()).toContain('Custom title')
    expect(wrapper.text()).toContain('Custom message')
    await wrapper.get('[data-testid="custom-error-retry"]').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })
})
