/**
 * ロケール管理 composable。
 * @nuxtjs/i18n の setLocale をラップし、アプリ全体の locale 切り替えを一元管理する。
 */
export const useLocale = () => {
  const { locale, setLocale, locales } = useI18n()

  /**
   * ロケールを切り替える。
   * ログイン済みの場合は API 経由で users.locale も更新する。
   * ログイン前の場合は @nuxtjs/i18n のみ更新する。
   */
  const changeLocale = async (newLocale: string) => {
    await setLocale(newLocale as Parameters<typeof setLocale>[0])
  }

  /**
   * ログイン成功後にサーバー設定の locale を適用する。
   * useAuthApi.ts のログイン処理から呼び出す。
   */
  const applyUserLocale = async (userLocale: string) => {
    if (userLocale && locale.value !== userLocale) {
      await setLocale(userLocale as Parameters<typeof setLocale>[0])
    }
  }

  return {
    locale,
    locales,
    changeLocale,
    applyUserLocale,
  }
}
