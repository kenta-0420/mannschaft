// @vitest-environment happy-dom
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { ref } from 'vue'
import { setActivePinia, createPinia } from 'pinia'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

// app/plugins/dayjs.ts が本番で行う拡張（テストでは plugin が走らないため）
dayjs.extend(utc)
dayjs.extend(timezone)

/**
 * pages/register.vue の登録送信 UT（CMP-261113-0210）。
 *
 * 検証観点:
 *   REG-001: locale は現在の表示言語（useI18n の locale）で送る（'ja' 固定にしない）
 *   REG-002: timezone はブラウザの IANA 名（取れなければ Asia/Tokyo）
 *   REG-003: 同一フレームで送信を 3 連打しても POST は 1 回だけ
 *   REG-004: register.vue に日本語直書きが無い（コメント行を除く）
 *   REG-005: register.vue が使う auth.register.* キーが 6 言語すべてに在り、ja 以外に日本語が残らない
 */

const localeRef = ref('en')
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key, locale: localeRef }))
mockNuxtImport('navigateTo', () => vi.fn())
mockNuxtImport('useServerHead', () => () => {})
mockNuxtImport('readPrefilledInputValue', () => () => '')
mockNuxtImport('useRoute', () => () => ({ path: '/register', params: {}, query: {} }))
mockNuxtImport('usePostalCodeValidation', () => () => ({
  ensureLoaded: async () => [],
  isSupported: () => false,
  validateFormat: () => true,
}))
mockNuxtImport('useAuthApi', () => () => ({ getGoogleAuthUrl: vi.fn() }))

const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => mockApi }))

const mockNotifyError = vi.fn()
const mockNotifySuccess = vi.fn()
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    error: mockNotifyError,
    success: mockNotifySuccess,
    info: vi.fn(),
    warn: vi.fn(),
    showError: mockNotifyError,
    showSuccess: mockNotifySuccess,
    showInfo: vi.fn(),
    showWarn: vi.fn(),
  }),
}))

const RegisterPage = (await import('~/pages/register.vue')).default

const stubs = {
  NuxtLink: { template: '<a><slot /></a>' },
  TermsModal: true,
  PrivacyModal: true,
  PasswordGeneratorActions: true,
}

async function mountFilled() {
  const wrapper = mount(RegisterPage, {
    attachTo: document.body,
    global: { plugins: [PrimeVue], mocks: { $t: (k: string) => k }, stubs },
  })
  await flushPromises()
  await wrapper.find('#email').setValue('user@example.com')
  await wrapper.find('#password').setValue('Password1!')
  await wrapper.find('#postalCode').setValue('123-4567')
  await wrapper.find('#birthDate').setValue('1990-01-01')
  await wrapper.find('#lastName').setValue('Smith')
  await wrapper.find('#firstName').setValue('John')
  await wrapper.find('#displayName').setValue('john_s')
  await wrapper.find('#termsAccepted input').setValue(true)
  await wrapper.find('#privacyPolicyAccepted input').setValue(true)
  return wrapper
}

beforeEach(() => {
  setActivePinia(createPinia())
  mockApi.mockReset()
  mockNotifyError.mockReset()
  mockNotifySuccess.mockReset()
  localeRef.value = 'en'
})

describe('pages/register.vue 登録送信', () => {
  it('REG-001: locale は現在の表示言語で送る', async () => {
    mockApi.mockResolvedValue({})
    const wrapper = await mountFilled()
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    await vi.waitFor(() => expect(mockApi).toHaveBeenCalledTimes(1))
    const body = mockApi.mock.calls[0]![1].body
    expect(body.locale).toBe('en')
    wrapper.unmount()
  })

  // ブラウザのタイムゾーンを固定して送信し、body.timezone を返す（実行環境の TZ に依存させない）
  async function submitWithBrowserTimezone(tz: string | undefined): Promise<string> {
    const spy = vi.spyOn(Intl.DateTimeFormat.prototype, 'resolvedOptions').mockReturnValue(
      { timeZone: tz } as Intl.ResolvedDateTimeFormatOptions,
    )
    try {
      mockApi.mockResolvedValue({})
      const wrapper = await mountFilled()
      await wrapper.find('form').trigger('submit')
      await vi.waitFor(() => expect(mockApi).toHaveBeenCalledTimes(1))
      const body = mockApi.mock.calls[0]![1].body
      wrapper.unmount()
      return body.timezone
    } finally {
      spy.mockRestore()
    }
  }

  it('REG-002: timezone はブラウザの IANA 名をそのまま送る', async () => {
    expect(await submitWithBrowserTimezone('America/New_York')).toBe('America/New_York')
  })

  it('REG-002b: UTC は Asia/Tokyo に置き換えず保持する', async () => {
    expect(await submitWithBrowserTimezone('UTC')).toBe('UTC')
  })

  it('REG-002c: 無効な値は既定の Asia/Tokyo に落ちる', async () => {
    expect(await submitWithBrowserTimezone('Not/AZone')).toBe('Asia/Tokyo')
  })

  it('REG-002d: 取得できない（undefined）ときは既定の Asia/Tokyo', async () => {
    expect(await submitWithBrowserTimezone(undefined)).toBe('Asia/Tokyo')
  })

  it('REG-003: 同一フレームで 3 連打しても POST は 1 回', async () => {
    mockApi.mockImplementation(() => new Promise(() => {}))
    const wrapper = await mountFilled()
    const form = wrapper.find('form')
    void form.trigger('submit')
    void form.trigger('submit')
    void form.trigger('submit')
    await vi.waitFor(() => expect(mockApi).toHaveBeenCalled())
    await flushPromises()

    expect(mockApi).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})

const here = dirname(fileURLToPath(import.meta.url))
const JA = /[぀-ヿ一-鿿]/
const KANA = /[぀-ヿ]/
const COMMENT = /^\s*(\/\/|\*|\/\*|<!--)/

describe('register.vue 日本語直書き番人', () => {
  const src = readFileSync(resolve(here, '../../../app/pages/register.vue'), 'utf-8')

  it('REG-004: 日本語リテラルの直書きが無い', () => {
    const hits = src
      .split(/\r?\n/)
      .map((l, i) => ({ l, n: i + 1 }))
      .filter(({ l }) => JA.test(l) && !COMMENT.test(l))
      .map(({ l, n }) => `register.vue:${n}: ${l.trim()}`)
    expect(hits).toEqual([])
  })

  const LANGS = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
  const load = (lang: string) =>
    JSON.parse(readFileSync(resolve(here, `../../../app/locales/${lang}/auth.json`), 'utf-8')).auth.register as Record<string, string>
  const used = [...new Set([...src.matchAll(/['"`]auth\.register\.([a-z0-9_]+)['"`]/g)].map((m) => m[1]!))]

  it('REG-005: 使用キーが存在する', () => {
    expect(used.length).toBeGreaterThan(10)
  })

  for (const lang of LANGS) {
    it(`REG-005: ${lang} に使用キーが全て在る`, () => {
      const dict = load(lang)
      expect(used.filter((k) => typeof dict[k] !== 'string' || dict[k].length === 0)).toEqual([])
    })
    if (lang !== 'ja') {
      it(`REG-005: ${lang} の auth.register に日本語が残っていない`, () => {
        const leaked = Object.entries(load(lang)).filter(([, v]) => (lang === 'zh' ? KANA : JA).test(v)).map(([k]) => k)
        expect(leaked).toEqual([])
      })
    }
  }
})
