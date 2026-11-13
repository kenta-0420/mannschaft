// @vitest-environment nuxt
// 実ページのDOMリンクを検証する。牧場の取得・操作と周辺部品は対象外。
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { useNuxtApp } from '#app'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RanchPageContent from './RanchPageContent.vue'

vi.mock('~/composables/useRanchState', async () => {
  const { ref } = await import('vue')
  return {
    provideRanchState: vi.fn(),
    useRanchState: () => ({
      loading: ref(false), failed: ref(false),
      state: ref({ owner: null, dinosaur: null }),
      load: vi.fn(),
      api: { command: { running: ref(false), pending: ref(null) } },
    }),
  }
})
vi.mock('~/composables/useRanchVisibility', () => ({ useRanchVisibility: () => ({ setVisible: vi.fn() }) }))

afterEach(() => { vi.restoreAllMocks() })

describe('牧場から振り返りへの入口', () => {
  it('entry未選択の想起リンクは個別想起画面ではなく振り返り入口へ進む', async () => {
    const { $i18n } = useNuxtApp()
    await $i18n.setLocale('ja')
    const wrapper = await mountSuspended(RanchPageContent, { global: {
      stubs: {
        NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
        PageHeader: true, Button: true, PageLoading: true, DashboardErrorState: true,
        WidgetRanch: true, RanchStatusPanel: true, RanchNaming: true,
        RanchSettingsPanel: true, RanchGuideModal: true,
      },
    } })
    try {
      const link = wrapper.findAll('a').find(item => item.text() === $i18n.t('ranch.recall'))
      expect(link, '牧場の実DOMに想起リンクがある').toBeDefined()
      // /reflections は本人の今日/自由テーマを取得する入口。recall個別画面はentryが必須。
      expect(link?.attributes('href')).toBe('/reflections')
    } finally { wrapper.unmount() }
  })
})
