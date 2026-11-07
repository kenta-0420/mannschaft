/**
 * locale.client.ts — 起動時（リロード時）にアカウント言語を表示言語と Cookie へ揃えるプラグイン
 *
 * auth.client.ts が localStorage から user を復元した後に実行される（l > a でアルファベット順後）。
 * ログイン中はアカウントに保存された言語（authStore.user.locale）を正とし、
 * Cookie (i18n_locale) が別の言語を指していてもアカウント言語へ揃える（CMP-261007-1243）。
 * 未ログイン（user なし）の場合は何もしない＝従来どおり Cookie / ブラウザ言語の検出に任せる。
 *
 * - SSR フェーズ: Cookie (i18n_locale) からロケールを確定（nuxt.config.ts detectBrowserLanguage.useCookie）
 * - クライアントフェーズ: このプラグインがアカウント言語で Cookie と表示を上書きする
 */
import { applyAccountLocaleTo, type AccountLocaleTarget } from '~/utils/accountLocale'

export default defineNuxtPlugin((nuxtApp) => {
  const authStore = useAuthStore()
  const userLocale = authStore.user?.locale
  if (!userLocale) return

  // プラグイン文脈では useI18n()（setup 専用）は呼べないため nuxtApp.$i18n（グローバル composer）を使う。
  const i18n = nuxtApp.$i18n as unknown as AccountLocaleTarget
  // メッセージ読み込みで app mount をブロックしないよう await しない（#1763/#1775 の既往）。
  // 失敗は握りつぶさずコンソールに出す。
  void applyAccountLocaleTo(i18n, userLocale).catch((error: unknown) => {
    console.error('[locale.client] アカウント言語の適用に失敗しました:', error)
  })
})
