// @vitest-environment nuxt
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia } from 'pinia'
import { defineComponent, h } from 'vue'
import { useRouter as useInjectedRouter } from 'vue-router'
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
    let injectedRouter: ReturnType<typeof useInjectedRouter> | undefined
    const harness = defineComponent({
      setup() {
        // 実RouterLinkと同じVue injectionから取得する。NuxtAppの別routerは監視しない。
        injectedRouter = useInjectedRouter()
        return () => h(SettingsIndexPage)
      },
    })
    const wrapper = await mountSuspended(harness)
    const router = injectedRouter
    if (!router) throw new Error('SETTINGS_LINK_ROUTER_MISSING')
    // 実リンクの遷移要求だけを観測し、遷移先のAPIはこの入口試験で実行しない。
    const push = vi.spyOn(router, 'push').mockResolvedValue(undefined)
    await wrapper.get(`a[href="${target}"]`).trigger('click', { button: 0 })
    expect(push).toHaveBeenCalledTimes(1)
    const requested = push.mock.calls[0]?.[0]
    expect(requested).toBeDefined()
    expect(router.resolve(requested!).path).toBe(target)
    expect(external.api).not.toHaveBeenCalled()
  })
})
