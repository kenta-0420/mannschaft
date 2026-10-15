export type LoginResult = {
  accessToken: string
  refreshToken: string
  userId: number
  fullName: string
  email: string
  mfaRequired?: boolean
  mfaSessionToken?: string
  reactivated?: boolean
}

export function useLoginSession() {
  const api = useApi()
  const authStore = useAuthStore()
  // イベントハンドラ内でuseRuntimeConfigを呼ばず、先回り更新へsetup時の設定を渡す。
  const runtimeConfig = useRuntimeConfig()
  const notification = useNotification()
  const route = useRoute()
  const { applyAccountLocale } = useLocale()
  const { t } = useI18n()

  async function completeLogin(
    tokens: { accessToken: string; refreshToken: string },
    passwordResult?: LoginResult,
  ): Promise<string> {
    let navigationTarget: string | null = null
    authStore.setTokens(tokens.accessToken, tokens.refreshToken)
    // ログイン成功直後に先回りリフレッシュタイマーを武装する（capture 済み runtimeConfig を渡す）。
    armProactiveRefresh(runtimeConfig, authStore)

    // /api/v1/users/me でフルプロフィール（systemRole・locale・avatarUrl 等）を一括取得
    try {
      const profile = await api<{
        data: {
          id: number
          email: string
          lastName: string
          firstName: string
          avatarUrl: string | null
          systemRole: string | null
          locale: string
          timezone: string | null
          status: string | null
        }
      }>('/api/v1/users/me')
      // PENDING_PARENTAL_CONSENT: 保護者同意待ち → 専用ページへリダイレクト
      // （setUser・設定同期は行わず、遷移先だけを決めて以降の処理を飛ばす）
      if (profile.data.status === 'PENDING_PARENTAL_CONSENT') {
        navigationTarget = '/parental-consent/pending'
      }
      else {
        await authStore.setUser({
          id: profile.data.id,
          email: profile.data.email,
          fullName: profile.data.lastName + ' ' + profile.data.firstName,
          profileImageUrl: profile.data.avatarUrl,
          systemRole: profile.data.systemRole ?? undefined,
          timezone: profile.data.timezone ?? undefined,
          locale: profile.data.locale || undefined,
        })
        if (profile.data.locale) {
          await applyAccountLocale(profile.data.locale)
        }
      }
    } catch {
      if (!passwordResult) {
        const redirect = route.query.redirect
        await authStore.serverLogout()
        if (typeof redirect === 'string') {
          await navigateTo({ path: '/login', query: { redirect } }, { replace: true })
        }
        throw new Error('profile')
      }
      // プロフィール取得失敗時はログインレスポンスの基本情報で続行
      await authStore.setUser({
        id: passwordResult.userId,
        email: passwordResult.email,
        fullName: passwordResult.fullName,
        profileImageUrl: null,
      })
    }

    // 保護者同意待ちで遷移先が既に決まっている場合は、設定同期も遷移先の再決定も行わない。
    if (!navigationTarget) {
      // ログイン成立直後にアカウント設定（外観・ナビ）をサーバーから同期する。
      // 新ブラウザ（シークレット等）で localStorage が空でも BEの保存済み設定が反映される。
      authStore.syncAccountSettings()

      // 遷移先の決定。
      //
      // 一般ユーザーの既定を `/`（ランディング）ではなく `/dashboard` にしている理由:
      // `/` には `guest` ミドルウェアが付いており、認証済みユーザーは即座に `/dashboard` へ
      // 弾き返される。`/` 経由にすると「認証済みユーザーが決して見ないランディングページ」を
      // 読み込んでから捨てることになり、遷移が二段になって待ち時間が伸びるだけで利点がない。
      navigationTarget = passwordResult?.reactivated
        ? '/account-restore'
        : (route.query.redirect as string)
          || (authStore.isSystemAdmin ? '/system-admin' : '/dashboard')

      notification.success(t('auth.login.success'))
    }
    return navigationTarget!
  }

  return { completeLogin }
}
