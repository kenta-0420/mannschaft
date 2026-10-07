import { describe, it, expect, beforeEach, vi } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { useNuxtApp } from '#imports'
import { useLocale } from '~/composables/useLocale'
import { useAuthStore } from '~/stores/useAuthStore'
import localeClientPlugin from '~/plugins/locale.client'

/**
 * CMP-261007-1243: アカウント言語を正として表示言語と i18n Cookie を揃える。
 *
 * 実物の @nuxtjs/i18n（nuxt テスト環境）で検証する。
 * - 保存直後（applyAccountLocale 呼び出し直後）に Cookie が新しい言語になり、完了時に表示言語も変わる
 * - 起動時（locale.client プラグイン）にアカウント言語へ揃う
 * - 未ログイン時は Cookie を尊重して何も変えない
 *
 * 言語メッセージの初回読み込みは dev ビルドで数十秒かかるため、各テストの timeout を長く取る。
 */

const LONG = 300_000

type I18nGlobal = {
  locale: { value: string }
  setLocale: (code: string) => Promise<void>
}

function i18nGlobal(): I18nGlobal {
  return useNuxtApp().$i18n as unknown as I18nGlobal
}

function cookieLocale(): string | undefined {
  return document.cookie
    .split('; ')
    .find((c) => c.startsWith('i18n_locale='))
    ?.split('=')[1]
}

type AuthUser = Parameters<ReturnType<typeof useAuthStore>['setUser']>[0]

const baseUser: AuthUser = {
  id: 1,
  email: 'locale@example.com',
  fullName: 'テスト 太郎',
  profileImageUrl: null,
}

async function mountUseLocale() {
  let api: ReturnType<typeof useLocale> | undefined
  await mountSuspended(
    defineComponent({
      setup() {
        api = useLocale()
        return () => h('div')
      },
    }),
  )
  if (!api) throw new Error('useLocale を取得できませんでした')
  return api
}

async function runLocalePlugin() {
  const nuxtApp = useNuxtApp()
  await nuxtApp.runWithContext(() => localeClientPlugin(nuxtApp))
}

async function waitForLocale(code: string) {
  const deadline = Date.now() + LONG
  while (i18nGlobal().locale.value !== code) {
    if (Date.now() > deadline) throw new Error(`locale が ${code} になりませんでした`)
    await new Promise((r) => setTimeout(r, 50))
  }
}

describe('useLocale.applyAccountLocale / locale.client（実 i18n）', () => {
  beforeEach(async () => {
    // Cookie=de・表示=de の「アカウント言語と食い違った」状態から始める
    await i18nGlobal().setLocale('de')
    expect(cookieLocale()).toBe('de')
    localStorage.clear()
    useAuthStore().user = null
  }, LONG)

  it('保存直後に Cookie が新しい言語になり、完了時に表示言語と authStore キャッシュも揃う', async () => {
    const authStore = useAuthStore()
    await authStore.setUser({ ...baseUser, locale: 'ja' })
    const { applyAccountLocale } = await mountUseLocale()

    // 実 setLocale の前に関所を置き、「切替先メッセージを読み込み中（未完了）」の状態を決定的に作る。
    // dev 環境で setLocale が数十秒〜返らない状況の再現。中身は実物の setLocale を呼ぶ。
    const global = i18nGlobal()
    const realSetLocale = global.setLocale.bind(global)
    let openGate: () => void = () => {}
    const gate = new Promise<void>((r) => {
      openGate = r
    })
    const spy = vi.spyOn(global, 'setLocale').mockImplementation(async (code: string) => {
      await gate
      await realSetLocale(code)
    })

    try {
      const pending = applyAccountLocale('en')
      // useCookie は watcher で document.cookie へ書くため、Vue の tick を進めて観測する。
      for (let i = 0; i < 20 && cookieLocale() !== 'en'; i++) {
        await nextTick()
      }
      // 表示言語の切替はまだ（読み込み中）だが、Cookie はアカウント言語になっている
      expect(spy).toHaveBeenCalledWith('en')
      expect(global.locale.value).toBe('de')
      expect(cookieLocale()).toBe('en')

      openGate()
      await pending
    } finally {
      openGate()
      spy.mockRestore()
    }

    expect(i18nGlobal().locale.value).toBe('en')
    expect(cookieLocale()).toBe('en')
    expect(authStore.user?.locale).toBe('en')
    expect(JSON.parse(localStorage.getItem('currentUser') ?? '{}').locale).toBe('en')
  }, LONG)

  it('ログイン時（user に locale 未設定の 2FA 経路など）もアカウント言語へ揃え、キャッシュに保存する', async () => {
    const authStore = useAuthStore()
    await authStore.setUser({ ...baseUser })
    const { applyAccountLocale } = await mountUseLocale()

    await applyAccountLocale('ko')

    expect(i18nGlobal().locale.value).toBe('ko')
    expect(cookieLocale()).toBe('ko')
    expect(authStore.user?.locale).toBe('ko')
  }, LONG)

  it('起動時（ログイン済み）は Cookie より authStore のアカウント言語を優先して揃える', async () => {
    const authStore = useAuthStore()
    await authStore.setUser({ ...baseUser, locale: 'en' })

    await runLocalePlugin()
    // プラグインは mount を止めないよう待たずに適用するが、Cookie は即座に揃う
    await nextTick()
    expect(cookieLocale()).toBe('en')
    await waitForLocale('en')
    expect(cookieLocale()).toBe('en')
  }, LONG)

  it('未ログイン時は Cookie の言語を尊重し、何も書き換えない', async () => {
    await runLocalePlugin()
    await new Promise((r) => setTimeout(r, 100))
    expect(i18nGlobal().locale.value).toBe('de')
    expect(cookieLocale()).toBe('de')
  }, LONG)
})
