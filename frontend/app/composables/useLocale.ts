import { applyAccountLocaleTo } from '~/utils/accountLocale'

/**
 * ロケール管理 composable。
 *
 * ログイン中はアカウントに保存された言語（users.locale）を正とし、
 * 表示言語・i18n Cookie・authStore のキャッシュ（localStorage）を同じ言語に揃える。
 * 言語設定の保存・ログイン・2FA・OAuth はすべて applyAccountLocale を通すこと。
 * 起動時（リロード）は plugins/locale.client.ts が同じ applyAccountLocaleTo を使う。
 */
export const useLocale = () => {
  const i18n = useI18n()
  const authStore = useAuthStore()

  /**
   * アカウント言語を適用する。
   * 1. authStore.user.locale を更新し、次回起動時（locale.client.ts）の復元元を揃える
   * 2. i18n Cookie を即座に書き換える（次回 SSR もアカウント言語になる）
   * 3. 表示言語を切り替える（メッセージ読み込み完了まで待つ）
   */
  const applyAccountLocale = async (accountLocale: string | null | undefined) => {
    if (!accountLocale || !isSupportedLocale(accountLocale)) return
    if (authStore.user && authStore.user.locale !== accountLocale) {
      await authStore.setUser({ ...authStore.user, locale: accountLocale })
    }
    await applyAccountLocaleTo(i18n, accountLocale)
  }

  return {
    locale: i18n.locale,
    locales: i18n.locales,
    applyAccountLocale,
  }
}
