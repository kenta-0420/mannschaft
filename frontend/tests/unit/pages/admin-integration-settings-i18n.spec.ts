// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createI18n } from 'vue-i18n'
import LineSettings from '~/pages/admin/line-settings.vue'
import SnsSettings from '~/pages/admin/sns-settings.vue'
import PageHeader from '~/components/PageHeader.vue'

const boundary = vi.hoisted(() => ({
  scope: { current: { type: 'team', id: 'test-team' } },
  guard: vi.fn(),
  getConfig: vi.fn(),
  listFeeds: vi.fn(),
  mutation: vi.fn(),
}))
mockNuxtImport('useScopeStore', () => () => boundary.scope)
mockNuxtImport('useAdminScopeGuard', () => boundary.guard)
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))
mockNuxtImport('useLineConfigApi', () => () => ({
  getConfig: boundary.getConfig,
  createConfig: boundary.mutation,
  updateConfig: boundary.mutation,
  deleteConfig: boundary.mutation,
}))
mockNuxtImport('useSnsFeedApi', () => () => ({
  listFeeds: boundary.listFeeds,
  createFeed: boundary.mutation,
  updateFeed: boundary.mutation,
  deleteFeed: boundary.mutation,
  previewFeed: boundary.mutation,
}))

// 本番の辞書を読み、翻訳関数・対象ページ・PageHeaderは実物で検証する。
const dictionaries = import.meta.glob('../../../app/locales/*/{common,org_sidebar}.json', {
  eager: true, import: 'default',
}) as Record<string, Record<string, unknown>>
const locales = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
type Locale = typeof locales[number]
const messages = Object.fromEntries(locales.map(locale => [locale, {
  ...dictionaries[`../../../app/locales/${locale}/common.json`],
  ...dictionaries[`../../../app/locales/${locale}/org_sidebar.json`],
}]))
const pages = [
  { name: 'LINE', component: LineSettings, titleKey: 'orgSidebar.lineSettings', subtitleKey: 'integrationSettings.lineSubtitle', read: boundary.getConfig },
  { name: 'SNS', component: SnsSettings, titleKey: 'orgSidebar.snsSettings', subtitleKey: 'integrationSettings.snsSubtitle', read: boundary.listFeeds },
] as const

function render(page: typeof pages[number], locale: Locale) {
  const i18n = createI18n({ legacy: false, locale, fallbackLocale: false, messages })
  const wrapper = mount(page.component, {
    global: {
      plugins: [i18n],
      components: { PageHeader },
      stubs: {
        BackButton: true, Button: true, InputText: true, ToggleSwitch: true,
        PageLoading: true, SectionCard: true, DataTable: true, Column: true,
        Dialog: true, Select: true, Tag: true, PersonalScopeUnsupportedNotice: true,
      },
      directives: { tooltip: () => {} },
    },
  })
  return { wrapper, i18n }
}

beforeEach(() => {
  vi.clearAllMocks()
  boundary.scope.current = { type: 'team', id: 'test-team' }
  boundary.getConfig.mockResolvedValue({ data: null })
  boundary.listFeeds.mockResolvedValue({ data: [] })
})

describe.each(pages)('$name設定のタイトル・副題（AC1〜4・AC6表示部分）', page => {
  it.each(locales)('%sの実辞書でloading時と空/null応答後に表示する', async locale => {
    const { wrapper, i18n } = render(page, locale)
    const assertHeader = () => {
      expect(wrapper.get('h1').text()).toBe(i18n.global.t(page.titleKey))
      expect(wrapper.get('[data-testid="page-header-subtitle"]').text()).toBe(i18n.global.t(page.subtitleKey))
      expect(i18n.global.te(page.titleKey)).toBe(true)
      expect(i18n.global.te(page.subtitleKey)).toBe(true)
      expect(i18n.global.t(page.subtitleKey).trim()).not.toBe('')
      if (locale !== 'ja') {
        expect(wrapper.get('h1').text()).not.toMatch(/[\u3040-\u30ff]/u)
        // 中国語には漢字を使うため、非日本語検査はかなに限定する。
        expect(wrapper.get('[data-testid="page-header-subtitle"]').text()).not.toMatch(/[\u3040-\u30ff]/u)
      }
    }
    assertHeader()
    await flushPromises()
    assertHeader()
    expect(page.read).toHaveBeenCalledExactlyOnceWith('team', 'test-team')
    expect(boundary.guard).toHaveBeenCalledWith('DEPUTY_ADMIN')
    expect(boundary.mutation).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('同じインスタンスのja→de→ja切替で4文言が追従し、再取得・保存しない', async () => {
    const { wrapper, i18n } = render(page, 'ja')
    await flushPromises()
    for (const locale of ['ja', 'de', 'ja'] as const) {
      i18n.global.locale.value = locale
      await nextTick()
      expect(wrapper.get('h1').text()).toBe(i18n.global.t(page.titleKey))
      expect(wrapper.get('[data-testid="page-header-subtitle"]').text()).toBe(i18n.global.t(page.subtitleKey))
    }
    expect(page.read).toHaveBeenCalledTimes(1)
    expect(boundary.mutation).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
