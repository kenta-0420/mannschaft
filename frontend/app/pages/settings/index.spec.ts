// @vitest-environment nuxt
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia } from 'pinia'
import { defineComponent, h } from 'vue'
import { createMemoryHistory, createRouter, RouterLink, useRouter as useInjectedRouter } from 'vue-router'
import { useNuxtApp, useState } from '#app'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import SettingsIndexPage from './index.vue'
import SettingsRanchSection from '~/components/settings/SettingsRanchSection.vue'
import { useAuthStore } from '~/stores/useAuthStore'

const external = vi.hoisted(() => ({ api: vi.fn() }))
mockNuxtImport('useApi', () => () => external.api)

let originalLocale: string
beforeEach(async () => {
  const nuxt = useNuxtApp()
  setActivePinia(nuxt.$pinia)
  originalLocale = nuxt.$i18n.locale.value
  const auth = useAuthStore()
  auth.$reset()
  auth.$patch({ user: {
    id: 1, email: 'synthetic@example.invalid', fullName: 'Synthetic',
    profileImageUrl: null, systemRole: 'MEMBER',
  } })
  await nuxt.runWithContext(() => {
    useState('settings-show-individual', () => false).value = false
    useState('settings-last-clicked', () => '').value = ''
  })
  external.api.mockReset()
  // 未参加・ウィジェット非表示の本人。入口の表示にこの取得を要求しない。
  external.api.mockResolvedValue({ data: {
    owner: null, dinosaur: null,
    widgets: [{ widgetKey: 'PERSONAL_DINOSAUR_RANCH', visible: false }],
  } })
})
afterEach(async () => {
  useAuthStore().$reset()
  await useNuxtApp().$i18n.setLocale(originalLocale as 'ja' | 'en' | 'zh' | 'ko' | 'es' | 'de')
  vi.restoreAllMocks()
})

describe('設定ハブの常時表示する本人牧場入口', () => {
  it.each(['ja', 'en'] as const)('%s: 個別設定を閉じた未参加本人にも既存訳の二リンクを表示する', async locale => {
    const i18n = useNuxtApp().$i18n
    await i18n.setLocale(locale)
    const wrapper = await mountSuspended(SettingsIndexPage)
    expect(wrapper.find('a[href="/settings/calendar-sync"]').exists()).toBe(false)
    const section = wrapper.getComponent(SettingsRanchSection)
    expect(section.get('h2').text()).toBe(i18n.t('ranch.title'))
    expect(section.get('a[href="/my/ranch"]').text()).toBe(i18n.t('ranch.settings.title'))
    expect(section.get('a[href="/my/ranch/results"]').text()).toBe(i18n.t('ranch.diagnosisResults.title'))
    expect(external.api).not.toHaveBeenCalled()
  })

  it.each(['/my/ranch', '/my/ranch/results'])('%s: 実NuxtLinkのクリックで本人routeへ遷移する', async target => {
    // 入口のリンク結線を実Vue Routerで検証する。Nuxtの認可middlewareは実機E2Eが担う。
    const destination = defineComponent({ render: () => null })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: ['/settings', '/my/ranch', '/my/ranch/results'].map(path => ({
        path,
        component: destination,
      })),
    })
    await router.push('/settings')
    await router.isReady()
    let injectedRouter: ReturnType<typeof useInjectedRouter> | undefined
    const harness = defineComponent({
      setup() {
        injectedRouter = useInjectedRouter()
        return () => h(SettingsIndexPage)
      },
    })
    const wrapper = await mountSuspended(harness, {
      global: {
        plugins: [router],
        // test-utils既定RouterLinkはuseLink欠如時にクリックをno-opにする。
        // スタブではなくvue-router本体を登録し、実navigateを通す。
        components: { RouterLink },
      },
    })
    expect(injectedRouter).toBe(router)
    expect(router.currentRoute.value.path).toBe('/settings')

    await wrapper.get(`a[href="${target}"]`).trigger('click', { button: 0 })

    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe(target), { timeout: 1000 })
    expect(external.api).not.toHaveBeenCalled()
  })
})
