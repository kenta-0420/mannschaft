/**
 * locale.client.ts — リロード時のロケール復元プラグイン
 *
 * auth.client.ts が localStorage から user を復元した後に実行される（l > a でアルファベット順後）。
 * authStore.user.locale が保持するロケールを i18n に再適用し、
 * リロード後もナビバーが日本語に戻るバグ（#2）を根治する。
 *
 * nuxt.config.ts の detectBrowserLanguage.useCookie:true と組み合わせることで、
 * SSR ハイドレーション mismatch も防ぐ。
 * - SSR フェーズ: Cookie (i18n_locale) からロケールを確定
 * - クライアントフェーズ: このプラグインが authStore のロケールで上書き（一致を保証）
 */
export default defineNuxtPlugin(async () => {
  const authStore = useAuthStore()
  // auth.client.ts が loadFromStorage() で user を復元した後なので、
  // user.locale が存在すれば i18n に反映する。
  const userLocale = authStore.user?.locale
  if (userLocale) {
    const { applyUserLocale } = useLocale()
    await applyUserLocale(userLocale)
  }
})
