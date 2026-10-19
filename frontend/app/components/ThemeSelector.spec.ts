// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { flushPromises, mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import PrimeVue from 'primevue/config'
import { useAppearanceStore } from '~/stores/useAppearanceStore'
import ja from '~/locales/ja/appearance.json'
import en from '~/locales/en/appearance.json'
import zh from '~/locales/zh/appearance.json'
import ko from '~/locales/ko/appearance.json'
import es from '~/locales/es/appearance.json'
import de from '~/locales/de/appearance.json'
import ThemeSelector from './ThemeSelector.vue'

const boundary = vi.hoisted(() => ({ api: vi.fn() }))
mockNuxtImport('useApi', () => () => boundary.api)
const messages = { ja, en, zh, ko, es, de }
type Locale = keyof typeof messages
const labels = {
  ja: ['テーマ', 'ライト', 'ダーク'], en: ['Theme', 'Light', 'Dark'],
  zh: ['主题', '浅色', '深色'], ko: ['테마', '라이트', '다크'],
  es: ['Tema', 'Claro', 'Oscuro'], de: ['Design', 'Hell', 'Dunkel'],
} satisfies Record<Locale, string[]>

function render(locale: Locale, theme: 'LIGHT' | 'DARK' = 'LIGHT') {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useAppearanceStore()
  store.theme = theme
  const i18n = createI18n({ legacy: false, locale, messages })
  const wrapper = mount(ThemeSelector, {
    attachTo: document.body,
    global: { plugins: [pinia, PrimeVue, i18n] },
  })
  return { wrapper, i18n, store }
}

describe('共通テーマ選択の6言語表示（直書き対策 第1段）', () => {
  beforeEach(() => {
    boundary.api.mockReset().mockResolvedValue({ data: {} })
    localStorage.clear()
    document.documentElement.classList.remove('dark', 'p-dark')
  })

  it.each(Object.keys(labels) as Locale[])('%s の正しい3ラベルを実表示し既存2択だけを提供する', (locale) => {
    const { wrapper, store } = render(locale)
    expect([wrapper.get('label').text(), ...wrapper.findAll('button').map(item => item.text())]).toEqual(labels[locale])
    expect(wrapper.text().replace(/\s/g, '')).toBe(labels[locale].join('').replace(/\s/g, ''))
    expect(store.theme).toBe('LIGHT')
    expect(wrapper.text()).not.toContain('appearance.theme')
    expect(boundary.api).not.toHaveBeenCalled()
  })

  it('locale切替は表示だけを変え選択値と保存状態を変えず要求しない', async () => {
    const { wrapper, i18n, store } = render('en', 'DARK')
    const savedAppearance = JSON.stringify({ theme: 'DARK', bgColor: '#f3efe0', darkBgColor: '#18181b' })
    localStorage.setItem('appearance', savedAppearance)
    i18n.global.locale.value = 'de'
    await flushPromises()
    expect([wrapper.get('label').text(), ...wrapper.findAll('button').map(item => item.text())]).toEqual(labels.de)
    expect(store.theme).toBe('DARK')
    expect(wrapper.findAll('button')[1]!.attributes('aria-pressed')).toBe('true')
    expect(boundary.api).not.toHaveBeenCalled()
    expect(localStorage.getItem('appearance')).toBe(savedAppearance)
  })

  it('テーマ変更だけが既存LIGHT/DARKを一度ずつ保存する', async () => {
    const { wrapper, store } = render('en')
    await wrapper.findAll('button')[1]!.trigger('click')
    await flushPromises()
    expect(store.theme).toBe('DARK')
    expect(boundary.api).toHaveBeenCalledTimes(1)
    expect(boundary.api).toHaveBeenLastCalledWith('/api/v1/settings/appearance', expect.objectContaining({ method: 'PUT', body: expect.objectContaining({ theme: 'DARK' }) }))
    await wrapper.findAll('button')[0]!.trigger('click')
    await flushPromises()
    expect(store.theme).toBe('LIGHT')
    expect(boundary.api).toHaveBeenCalledTimes(2)
    expect(boundary.api).toHaveBeenLastCalledWith('/api/v1/settings/appearance', expect.objectContaining({ method: 'PUT', body: expect.objectContaining({ theme: 'LIGHT' }) }))
  })

  it('外部保存失敗でも既存のローカル選択を維持し再要求しない', async () => {
    boundary.api.mockRejectedValueOnce(new Error('unavailable'))
    const { wrapper, store } = render('en')
    await wrapper.findAll('button')[1]!.trigger('click')
    await flushPromises()
    expect(store.theme).toBe('DARK')
    expect(wrapper.findAll('button')[1]!.attributes('aria-pressed')).toBe('true')
    expect(boundary.api).toHaveBeenCalledTimes(1)
  })
})
