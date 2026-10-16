// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import ContactInvitePanel from './ContactInvitePanel.vue'
import { createI18n } from 'vue-i18n'
import PrimeVue from 'primevue/config'
import en from '~/locales/en/contact.json'
import common from '~/locales/en/common.json'

const api = vi.hoisted(() => ({ listInviteTokens: vi.fn(), createInviteToken: vi.fn() }))
const errors = vi.hoisted(() => ({ captureQuiet: vi.fn(), handleApiError: vi.fn() }))
mockNuxtImport('useContactApi', () => () => api)
mockNuxtImport('useErrorReport', () => () => errors)
mockNuxtImport('useErrorHandler', () => () => errors)
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))
mockNuxtImport('useDatetime', () => () => ({ formatDate: (value: string) => value }))

async function mountPanel(showCreateForm = false) {
  const wrapper = mount(ContactInvitePanel, {
    props: { showCreateForm },
    global: {
      plugins: [
        PrimeVue,
        createI18n({ legacy: false, locale: 'en', messages: { en: { ...common, ...en } } }),
      ],
      stubs: {
        DashboardEmptyState: {
          props: ['message'],
          template: '<p data-testid="contact-empty">{{ message }}</p>',
        },
        QrCodeImage: {
          props: ['value'],
          template: '<output data-testid="contact-qr">{{ value }}</output>',
        },
      },
    },
  })
  await flushPromises()
  return wrapper
}

describe('個人連絡先QR管理', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-16T03:00:00Z'))
    vi.clearAllMocks()
    api.listInviteTokens.mockReset().mockResolvedValue({ data: [] })
    api.createInviteToken.mockReset()
  })
  afterEach(() => vi.useRealTimers())

  it('一覧取得失敗を空と表示せず、再試行で一覧を取得する', async () => {
    api.listInviteTokens.mockRejectedValueOnce(new Error('offline'))
    const wrapper = await mountPanel()
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="contact-empty"]').exists()).toBe(false)
    await wrapper.get('[data-testid="load-error-state-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="contact-empty"]').exists()).toBe(true)
    expect(api.listInviteTokens).toHaveBeenCalledTimes(2)
  })

  it('開いただけでは発行せず、利用者の発行操作では7日・1回を送ってQRを表示する', async () => {
    const wrapper = await mountPanel(true)
    expect(api.createInviteToken).not.toHaveBeenCalled()
    api.createInviteToken.mockResolvedValue({
      data: {
        id: 1,
        inviteUrl: 'https://example.test/contact-invite/token',
        label: null,
        expiresAt: '2026-10-23T12:00:00',
        maxUses: 1,
        usedCount: 0,
      },
    })
    const submit = wrapper.findAll('button').find((b) => b.find('.pi-link').exists())!
    await submit.trigger('click')
    await flushPromises()
    expect(api.createInviteToken).toHaveBeenCalledWith({
      label: undefined,
      maxUses: 1,
      expiresIn: '7d',
    })
    expect(wrapper.get('[data-testid="contact-qr"]').text()).toContain('/contact-invite/token')
  })

  it('発行失敗ではQRを作らずフォームを保持する', async () => {
    api.createInviteToken.mockRejectedValue(new Error('offline'))
    const wrapper = await mountPanel(true)
    const submit = wrapper.findAll('button').find((b) => b.find('.pi-link').exists())!
    await submit.trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="contact-qr"]').exists()).toBe(false)
    expect(wrapper.find('input').exists()).toBe(true)
  })

  it('期限・回数上限nullの既存トークンもQRを表示する', async () => {
    api.listInviteTokens.mockResolvedValue({
      data: [
        {
          id: 2,
          inviteUrl: 'https://example.test/contact-invite/unlimited',
          label: 'Unlimited',
          expiresAt: null,
          maxUses: null,
          usedCount: 0,
        },
      ],
    })
    const wrapper = await mountPanel()
    expect(wrapper.get('[data-testid="contact-qr"]').text()).toContain('/contact-invite/unlimited')
    expect(wrapper.text()).toContain('∞')
    expect(wrapper.text()).not.toContain('Invalid Date')
    expect(api.createInviteToken).not.toHaveBeenCalled()
  })

  it('無期限・回数無制限を明示選択したらBEのunlimited契約を送る', async () => {
    const wrapper = await mountPanel(true)
    const selects = wrapper.findAllComponents({ name: 'Select' })
    expect(selects).toHaveLength(2)
    const expiry = selects.find((select) =>
      (select.props('options') as Array<{ value: unknown }>).some(
        (option) => option.value === '7d',
      ),
    )!
    const maxUses = selects.find((select) => select !== expiry)!
    const expiryOptions = expiry.props('options') as Array<{ label: string; value: string | null }>
    const never = expiryOptions.find(
      (option) => option.value === null || option.value === 'unlimited',
    )!
    const usageOptions = maxUses.props('options') as Array<{ label: string; value: number | null }>
    const unlimited = usageOptions.find((option) => option.value === null)!
    expiry.vm.$emit('update:modelValue', never.value)
    maxUses.vm.$emit('update:modelValue', unlimited.value)
    await wrapper.vm.$nextTick()
    api.createInviteToken.mockRejectedValue(new Error('stop after observing request'))
    const submit = wrapper.findAll('button').find((b) => b.find('.pi-link').exists())!
    await submit.trigger('click')
    await flushPromises()
    const sent = JSON.parse(JSON.stringify(api.createInviteToken.mock.calls[0]![0]))
    expect(sent.expiresIn).toBe('unlimited')
    expect(sent.maxUses ?? null).toBeNull()
  })
})
