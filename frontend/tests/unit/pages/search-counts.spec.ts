import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { useCookie, useNuxtApp } from '#app'
import { nextTick } from 'vue'
import type { Composer, VueMessageType } from 'vue-i18n'
import type { LocaleMessage } from '@intlify/core-base'
import jaCommon from '~/locales/ja/common.json'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import SearchPage from '~/pages/search/index.vue'
import PageLoading from '~/components/PageLoading.vue'
import { useAuthStore } from '~/stores/useAuthStore'

// DTO/OpenAPI が提供する現行9種別の応答を固定する。設計上の未実装応答をmockしない。
const { search, notification } = vi.hoisted(() => ({
  search: vi.fn(),
  notification: { error: vi.fn() },
}))
vi.mock('~/composables/useSearchApi', () => ({ useSearchApi: () => ({ search }) }))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notification }))

const kinds = ['schedules', 'events', 'reservations', 'shifts', 'safetyChecks', 'queues', 'teams', 'organizations', 'users']
const i18n = () => useNuxtApp().$i18n as unknown as Composer & { setLocale: (locale: string) => Promise<void> }
const localeCookie = () => useNuxtApp().runWithContext(() => useCookie<string | null | undefined>('i18n_locale'))
const translated = (key: string, values: Record<string, number> = {}) => i18n().t(key, values)
function response(count: number) {
  return {
    data: {
      query: 'native0700', executionTimeMs: 1,
      counts: Object.fromEntries(kinds.map(kind => [kind, count])),
      results: Object.fromEntries(kinds.map(kind => [kind, Array.from({ length: Math.min(count, 10) }, (_, index) => {
        const field = kind === 'users' ? 'fullName' : ['teams', 'organizations'].includes(kind) ? 'name'
          : kind === 'reservations' ? 'purpose' : kind === 'queues' ? 'guestName' : 'title'
        return { id: index + 1, [field]: `${kind}-native0700-${index + 1}` }
      })])),
    },
  }
}

describe('横断検索の現行件数契約', () => {
  let previousLocale: string
  let previousMessages: LocaleMessage<VueMessageType>
  let previousLocaleCookie: string | null | undefined

  beforeEach(async () => {
    // happy-dom の環境言語に依存せず、正本の日本語を実i18nへ読み込む。スタブは使わない。
    previousLocale = i18n().locale.value
    // 実カタログの非同期mergeが元オブジェクトを更新するため、復元用はJSON正本の値を退避する。
    previousMessages = JSON.parse(JSON.stringify(i18n().getLocaleMessage<LocaleMessage<VueMessageType>>('ja')))
    previousLocaleCookie = (await localeCookie()).value
    await i18n().setLocale('ja')
    i18n().setLocaleMessage<LocaleMessage<VueMessageType>>('ja', { ...previousMessages, ...jaCommon })
    useAuthStore().user = { id: 700, email: 'unit-search@example.test', fullName: '検索テスト', profileImageUrl: null }
    search.mockReset()
    notification.error.mockClear()
  })

  afterEach(async () => {
    await i18n().setLocale(previousLocale)
    i18n().setLocaleMessage<LocaleMessage<VueMessageType>>('ja', previousMessages)
    const cookie = await localeCookie()
    cookie.value = previousLocaleCookie
    await nextTick()
  })

  async function settleSearch(wrapper: Awaited<ReturnType<typeof mountSuspended>>) {
    await flushPromises()
    // 検索APIの後に行われる実router.replaceまで待ち、途中のloadingを結果と混同しない。
    await vi.waitFor(() => expect(wrapper.findComponent(PageLoading).exists()).toBe(false))
  }

  async function submit() {
    const wrapper = await mountSuspended(SearchPage, { route: '/search' })
    await wrapper.find('input').setValue('native0700')
    expect(wrapper.find('form').exists()).toBe(true)
    expect(wrapper.findAll('button').map(button => button.text())).toContain(translated('button.search'))
    await wrapper.find('form').trigger('submit')
    await settleSearch(wrapper)
    return wrapper
  }

  it('9種別それぞれの総数11と先頭10件を表示する', async () => {
    search.mockResolvedValue(response(11))
    const wrapper = await submit()
    for (let index = 0; index < kinds.length; index++) {
      expect(wrapper.text()).toContain(`${translated(`globalSearch.kinds.${kinds[index]}`)} (11)`)
      expect(wrapper.text()).toContain(`${kinds[index]}-native0700-10`)
      expect(wrapper.text()).not.toContain(`${kinds[index]}-native0700-11`)
    }
    expect(translated('globalSearch.kinds.schedules')).toBe('予定')
    expect(wrapper.findAll('li')).toHaveLength(90)
    expect(wrapper.text()).toContain(`${translated('globalSearch.all')} (99)`)
    expect(wrapper.findAll('p').filter(item => item.text() === translated('globalSearch.showing', { shown: 10, total: 11 }))).toHaveLength(9)
    expect(notification.error).not.toHaveBeenCalled()
  })

  it('総数0の成功応答は空状態で表示する', async () => {
    search.mockResolvedValue(response(0))
    const wrapper = await submit()
    expect(wrapper.text()).toContain(translated('globalSearch.empty'))
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(false)
    expect(notification.error).not.toHaveBeenCalled()
  })

  it('検索失敗は空状態と区別し再試行で同じ検索を行う', async () => {
    search.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce(response(11))
    const wrapper = await submit()
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain(translated('globalSearch.empty'))
    const retry = wrapper.find('[data-testid="load-error-state-retry"]')
    expect(retry.text()).toBe(translated('loadErrorState.retry'))
    await retry.trigger('click')
    await settleSearch(wrapper)
    expect(wrapper.text()).toContain(`${translated('globalSearch.kinds.schedules')} (11)`)
    expect(search).toHaveBeenCalledTimes(2)
    expect(search).toHaveBeenNthCalledWith(1, { q: 'native0700' })
    expect(search).toHaveBeenNthCalledWith(2, { q: 'native0700' })
  })
})
