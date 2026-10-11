/**
 * primevue-locale.ts — PrimeVue 既定文言（aria-label・日付・ページ送り）を表示言語に追従させるプラグイン（CMP-261007-2053）
 *
 * universal プラグイン（SSR でも適用する）。辞書は app/utils/primevueLocales.ts の 6 言語完全辞書を使う。
 * 実行順: @primevue/nuxt-module と @nuxtjs/i18n のプラグインはモジュール由来で、アプリの plugins/ より先に登録される。
 * そのため $primevue（PrimeVue の config）と $i18n（locale）がこの時点で参照できる。
 * i18n.locale の変化（applyAccountLocale の setLocale 完了を含む）には bindPrimeVueLocale の watch が追従する。
 */
import type { PrimeVueLocaleOptions } from 'primevue/config'
import type { Ref } from 'vue'
import { bindPrimeVueLocale } from '~/utils/primevueLocales'

export default defineNuxtPlugin((nuxtApp) => {
  const i18n = nuxtApp.$i18n as unknown as { locale: Ref<string> }
  const primeVue = nuxtApp.vueApp.config.globalProperties.$primevue as
    | { config: { locale?: PrimeVueLocaleOptions } }
    | undefined
  if (!primeVue) {
    throw new Error('[primevue-locale] $primevue が未登録です（@primevue/nuxt-module より後に実行される必要があります）')
  }
  bindPrimeVueLocale(primeVue.config, i18n.locale)
})
