// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'
import { defineComponent, onMounted, ref } from 'vue'
import { createPinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { flushPromises, mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import PrimeVue from 'primevue/config'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
import type { AuthSessionResponse } from '~/types/auth'
import settings from '~/locales/en/settings.json'
import common from '~/locales/en/common.json'
import SettingsSecuritySection from './SettingsSecuritySection.vue'

dayjs.extend(utc)
dayjs.extend(timezone)
const state = vi.hoisted(() => ({ api: vi.fn() }))
mockNuxtImport('useApi', () => () => state.api)

describe('セッションAPIのcurrent公開契約', () => {
  it('current:trueだけの本人行に現在タグを表示し失効CTAを出さず、他行のみ失効できる', async () => {
    state.api.mockReset().mockResolvedValue({ data: [
      { id: 1, userAgent: 'CurrentDevice', ipAddress: '192.0.2.1', createdAt: '2026-10-17T03:00:00Z', current: true },
      { id: 2, userAgent: 'OtherDevice', ipAddress: '192.0.2.2', createdAt: '2026-10-17T03:00:00Z', current: false },
    ] })
    const wrapper = mount(defineComponent({
      components: { SettingsSecuritySection },
      setup() {
        const sessions = ref<AuthSessionResponse[]>([])
        const { getSessions } = useAuthApi()
        onMounted(async () => { sessions.value = (await getSessions()).data })
        return { sessions }
      },
      template: `<SettingsSecuritySection :sessions="sessions" :totp-setup="null"
        :setting2fa="false" :regenerating="false" :credentials="[]" :backup-codes="[]"
        :show-backup-codes-dialog="false" :rename-dialog="false" new-device-name="" />`,
    }), {
      global: { plugins: [createPinia(), PrimeVue, createI18n({
        legacy: false, locale: 'en',
        messages: { ja: {}, en: { ...common, ...settings }, zh: {}, ko: {}, es: {}, de: {} },
      })] },
    })
    await flushPromises()

    const rowFor = (name: string) => wrapper.findAll('p').find(node => node.text().startsWith(name))!.element.parentElement!.parentElement!
    const currentRow = rowFor('CurrentDevice')
    expect(currentRow.querySelector('[data-pc-name="tag"]')?.textContent).toBe('Current')
    expect(currentRow.querySelectorAll('button')).toHaveLength(0)
    const otherRow = rowFor('OtherDevice')
    expect(otherRow.querySelector('[data-pc-name="tag"]')).toBeNull()
    expect(otherRow.querySelectorAll('button')).toHaveLength(1)
    otherRow.querySelector('button')!.click()
    await flushPromises()
    expect(wrapper.findComponent(SettingsSecuritySection).emitted('revokeSession')).toEqual([[2]])
    expect(state.api.mock.calls).toEqual([['/api/v1/auth/sessions']])
  })
})
