<script setup lang="ts">
definePageMeta({
  layout: 'auth',
  middleware: 'guest',
})

const email = ref('')
const password = ref('')
const loading = ref(false)
const googleLoading = ref(false)

const api = useApi()
const authStore = useAuthStore()
// 先回りリフレッシュ武装に渡す runtimeConfig は、必ず setup コンテキストで capture しておく
// （イベントハンドラ内で useRuntimeConfig() を呼ぶ落とし穴を避ける。armProactiveRefresh 参照）。
const runtimeConfig = useRuntimeConfig()
const { getGoogleAuthUrl } = useAuthApi()
const notification = useNotification()
const route = useRoute()
const { applyUserLocale } = useLocale()
const { t } = useI18n()

// OAuth競合メッセージ（/auth/oauth/callback → ?oauthConflict=true で遷移してきた場合）
const oauthConflictMessage = computed<string | null>(() => {
  return route.query.oauthConflict === 'true' ? t('auth.oauth.conflict_message') : null
})

// パスワード変更・セッション失効などのログアウト後にログイン画面へ遷移した場合、
// ?reason=xxx クエリパラメータに基づいて情報バナーを表示する。
const sessionNoticeMessage = computed<string | null>(() => {
  const reason = route.query.reason
  if (reason === 'password_changed') {
    return t('auth.session_notice.password_changed')
  }
  if (reason === 'session_expired') {
    return t('auth.session_notice.session_expired')
  }
  return null
})

async function loginWithGoogle() {
  googleLoading.value = true
  try {
    const res = await getGoogleAuthUrl()
    window.location.href = res.data.authUrl
  } catch {
    notification.error(t('auth.oauth.callback_error'))
    googleLoading.value = false
  }
}

async function handleLogin() {
  loading.value = true
  // 遷移先。ログイン処理が成功した経路でのみ設定し、実際の遷移は try/catch の外で行う。
  // try の中で navigateTo すると、遷移側の失敗を catch が拾って
  // 「メールアドレスまたはパスワードが不一致」という誤った文言を出してしまうため。
  let navigationTarget: string | null = null
  try {
    const data = await api<{
      data: {
        accessToken: string
        refreshToken: string
        userId: number
        fullName: string
        email: string
        mfaRequired?: boolean
        mfaSessionToken?: string
        reactivated?: boolean
      }
    }>('/api/v1/auth/login', {
      method: 'POST',
      body: { email: email.value, password: password.value },
    })
    if (data.data.mfaRequired) {
      navigationTarget = `/2fa-verify?session=${data.data.mfaSessionToken}`
    } else {
      authStore.setTokens(data.data.accessToken, data.data.refreshToken)
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
            await applyUserLocale(profile.data.locale)
          }
        }
      } catch {
        // プロフィール取得失敗時はログインレスポンスの基本情報で続行
        await authStore.setUser({
          id: data.data.userId,
          email: data.data.email,
          fullName: data.data.fullName,
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
        navigationTarget = data.data.reactivated
          ? '/account-restore'
          : (route.query.redirect as string)
            || (authStore.isSystemAdmin ? '/system-admin' : '/dashboard')

        notification.success(data.data.reactivated ? 'ログインしました' : 'ログイン成功')
      }
    }
  } catch (e: unknown) {
    // backend は AuthErrorCode を { error: { code, message } } 形式で返す。
    // code を判定して文言を出し分ける（AUTH_003 ロック等を握りつぶさない）。
    const err = e as { data?: { error?: { code?: string; message?: string } } }
    const code = err?.data?.error?.code
    if (code === 'AUTH_003') {
      // アカウントロック（5回失敗で30分ロック）
      notification.error(t('auth.login.account_locked'), t('auth.login.account_locked_detail'))
    } else if (code === 'AUTH_002') {
      // メール未確認
      notification.error(
        t('auth.login.email_not_verified'),
        t('auth.login.email_not_verified_detail'),
      )
    } else {
      // AUTH_001 を含む既定（メールアドレスまたはパスワード不一致）
      notification.error(t('auth.login.failed'), t('auth.login.invalid_credentials'))
    }
  }

  if (!navigationTarget) {
    // 認証に失敗した経路。遷移しないのでここで操作可能状態へ戻す。
    loading.value = false
    return
  }

  // navigateTo は必ず await すること。
  //
  // await しないと遷移完了を待たずに loading が落ち、遷移が進行中にもかかわらず
  // ボタンが平常状態へ戻る。遷移先ページの初回読み込みに時間がかかる状況
  // （dev サーバーのオンデマンドビルド・低速回線）では、画面がログインフォームのまま
  // 無反応に見えるため、ユーザーは「ログインに失敗した」と誤認して再度ログインを試みる。
  // 実測では初回遷移に最大 56 秒を要し、この間ずっと無反応に見えていた。
  // await して遷移完了まで loading を保持すれば、進行中であることが UI に正しく表れる。
  //
  // なお遷移は try/catch の外に置いている。中に置くと遷移側の失敗を上の catch が拾い、
  // ログインは成功しているのに「メールアドレスまたはパスワードが不一致」と誤報するため。
  try {
    await navigateTo(navigationTarget)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <form @submit.prevent="handleLogin">
    <div class="flex flex-col gap-4">
      <!-- 戻るリンク -->
      <NuxtLink
        to="/"
        class="inline-flex items-center gap-2 text-sm text-surface-500 transition-colors hover:text-primary"
      >
        {{ $t('landing.features_detail.back_to_top') }}
      </NuxtLink>

      <!-- パスワード変更後・セッション失効後の案内バナー（info色・エラーではない） -->
      <div
        v-if="sessionNoticeMessage"
        class="flex items-start gap-2 rounded-lg border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-800 dark:border-blue-700 dark:bg-blue-950 dark:text-blue-200"
        role="status"
        aria-live="polite"
      >
        <i class="pi pi-info-circle mt-0.5 shrink-0 text-blue-500 dark:text-blue-400" />
        <span>{{ sessionNoticeMessage }}</span>
      </div>

      <!-- OAuth競合バナー（メールアドレス一致で連携確認メール送信済み） -->
      <div
        v-if="oauthConflictMessage"
        class="flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-200"
        role="alert"
        aria-live="polite"
      >
        <i class="pi pi-exclamation-triangle mt-0.5 shrink-0 text-amber-500 dark:text-amber-400" />
        <span>{{ oauthConflictMessage }}</span>
      </div>

      <!-- Google ログインボタン -->
      <Button
        type="button"
        :label="$t('auth.oauth.google_login')"
        icon="pi pi-google"
        severity="secondary"
        outlined
        class="w-full"
        :loading="googleLoading"
        @click="loginWithGoogle"
      />

      <!-- セパレーター -->
      <div class="flex items-center gap-3">
        <div class="flex-1 border-t border-surface-200 dark:border-surface-600" />
        <span class="text-sm text-surface-400">{{ $t('auth.oauth.or') }}</span>
        <div class="flex-1 border-t border-surface-200 dark:border-surface-600" />
      </div>

      <div class="flex flex-col gap-2">
        <label for="email">{{ t('auth.login.email') }}</label>
        <InputText
          id="email"
          v-model="email"
          type="email"
          placeholder="example@mannschaft.app"
          required
        />
      </div>
      <div class="flex flex-col gap-2">
        <label for="password">{{ t('auth.login.password') }}</label>
        <Password
          v-model="password"
          input-id="password"
          :feedback="false"
          toggle-mask
          fluid
          required
        />
      </div>
      <Button type="submit" :label="t('auth.login.submit')" icon="pi pi-sign-in" :loading="loading" class="mt-2" />
      <div class="flex flex-col items-center gap-2">
        <NuxtLink to="/forgot-password" class="text-sm text-primary hover:underline">
          {{ t('auth.login.forgot_password') }}
        </NuxtLink>
        <NuxtLink to="/register" class="text-sm text-primary hover:underline">
          {{ t('auth.register.title') }}
        </NuxtLink>
      </div>
    </div>
  </form>
</template>
