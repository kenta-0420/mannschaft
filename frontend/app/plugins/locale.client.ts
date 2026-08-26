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
export default defineNuxtPlugin((nuxtApp) => {
  const authStore = useAuthStore()
  // auth.client.ts が loadFromStorage() で user を復元した後なので、
  // user.locale が存在すれば i18n に反映する。
  const userLocale = authStore.user?.locale
  if (!userLocale) return

  // プラグイン文脈では useI18n()（setup 専用）は呼べない。
  // primevue-locale.client.ts と同じく nuxtApp.$i18n（グローバルインスタンス）経由で設定する。
  const i18n = nuxtApp.$i18n as
    | { locale: { value: string }; setLocale: (code: string) => Promise<void> }
    | undefined
  if (i18n && i18n.locale.value !== userLocale) {
    // setLocale 自体は必ず呼ぶ（リロード後の lang 追従を壊さない）が、
    // ロケールチャンク取得のハング/失敗で app mount をブロックしないよう
    // 非ブロッキング化（await しない）＋ 失敗は握って mount を止めない。
    // ※ auth.client と同型の async プラグイン mount ブロック保険（#1763/#1775 の類似既往）。
    void i18n.setLocale(userLocale).catch((error) => {
      console.error('[locale.client] setLocale failed:', error)
    })
  }
})
