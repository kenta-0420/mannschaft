<script setup lang="ts">
import { PasskeyLoginError } from '~/composables/useWebAuthnLogin'
import { useLoginSession, type LoginResult } from '~/composables/useLoginSession'

definePageMeta({
  layout: 'auth',
  middleware: 'guest',
})

// Vue の hydration より先に送信された場合だけ、ブラウザ標準の GET 送信を止める。
// 静的スクリプトとして head に置き、クリック直後の loading 表示と送信予約を担当する。
const PRE_HYDRATION_SUBMIT_GUARD = `(()=>{const e="login-form",t="login:hydrated",i=n=>{const o=n.target;if(!(o instanceof HTMLFormElement)||o.id!==e)return;n.preventDefault();n.stopImmediatePropagation();if(o.dataset.preHydrationSubmit==="pending")return;o.dataset.preHydrationSubmit="pending";const d=o.querySelector("[data-login-submit]");if(!d)return;d.disabled=true;d.setAttribute("aria-busy","true");d.setAttribute("data-p-disabled","true");d.setAttribute("data-p","loading");d.classList.add("p-button-loading");const a=d.querySelector(".p-button-icon");if(a){a.className="p-button-loading-icon p-button-icon p-button-icon-left pi pi-spinner pi-spin";a.removeAttribute("data-p");a.setAttribute("data-pc-section","loadingicon")}};document.addEventListener("submit",i,true);document.addEventListener(t,()=>document.removeEventListener("submit",i,true),{once:true})})();`

useServerHead({
  script: [
    {
      key: 'login-pre-hydration-submit-guard',
      innerHTML: PRE_HYDRATION_SUBMIT_GUARD,
      tagPosition: 'head',
    },
  ],
})

// ハイドレーション前に入力された値（パスワードマネージャの自動入力を含む）を取り込む。
// ref('') のままだとハイドレーション時に空で上書きされて消える。必ずセットアップ時に読むこと。
const email = ref(readPrefilledInputValue('email'))
const password = ref(readPrefilledInputValue('password'))
const passwordInputProps: Record<string, string> = { 'data-allow-mismatch': '' }
const loading = ref(
  import.meta.client
  && document.getElementById('login-form')?.dataset.preHydrationSubmit === 'pending',
)
const googleLoading = ref(false)
const passkeyLoading = ref(false)
let passwordPending = false
const busy = computed(() => loading.value || googleLoading.value || passkeyLoading.value)
const { loginWithPasskey } = useWebAuthnLogin()

const api = useApi()
const { completeLogin } = useLoginSession()
const { getGoogleAuthUrl } = useAuthApi()
const notification = useNotification()
const route = useRoute()
const { t } = useI18n()

onMounted(async () => {
  const form = document.getElementById('login-form') as HTMLFormElement | null
  const shouldReplaySubmit = form?.dataset.preHydrationSubmit === 'pending'

  // ここからは Vue の @submit.prevent が処理できるため、head の一時ガードを解除する。
  document.dispatchEvent(new Event('login:hydrated'))

  if (form && shouldReplaySubmit) {
    await nextTick()
    if (!form.reportValidity()) {
      loading.value = false
      return
    }
    form.requestSubmit()
  }
})

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
  if (busy.value) return
  googleLoading.value = true
  try {
    const res = await getGoogleAuthUrl()
    window.location.href = res.data.authUrl
  } catch {
    notification.error(t('auth.oauth.callback_error'))
    googleLoading.value = false
  }
}

async function handlePasskeyLogin() {
  if (busy.value) return
  // パスワード欄のrequiredを評価せず、共用メール欄のみブラウザ検証する。
  const emailInput = document.getElementById('email') as HTMLInputElement | null
  if (!emailInput?.reportValidity()) return
  passkeyLoading.value = true
  let navigationTarget: string | null = null
  try {
    const tokens = await loginWithPasskey(email.value)
    if (tokens) navigationTarget = await completeLogin(tokens)
  } catch (error: unknown) {
    const detail = error instanceof PasskeyLoginError
      ? error.reason
      : error instanceof Error && error.message === 'profile' ? 'profile_failed' : 'failed'
    notification.error(t('auth.login.failed'), t(`auth.login.passkey_${detail}`))
  }
  if (!navigationTarget) {
    passkeyLoading.value = false
    return
  }
  try {
    await navigateTo(navigationTarget)
  } finally {
    passkeyLoading.value = false
  }
}

async function handleLogin() {
  if (passwordPending || passkeyLoading.value || googleLoading.value) return
  passwordPending = true
  loading.value = true
  // 遷移先。ログイン処理が成功した経路でのみ設定し、実際の遷移は try/catch の外で行う。
  // try の中で navigateTo すると、遷移側の失敗を catch が拾って
  // 「メールアドレスまたはパスワードが不一致」という誤った文言を出してしまうため。
  let navigationTarget: string | null = null
  try {
    const data = await api<{
      data: LoginResult & {
        // MfaRequiredResponse（CMP-261012-0910 AC-27）。旧 BE 応答では欠落しうる。
        primaryMethod?: 'TOTP' | 'EMAIL' | 'BACKUP_CODE' | null
        availableMethods?: Array<'TOTP' | 'EMAIL' | 'BACKUP_CODE'> | null
      }
    }>('/api/v1/auth/login', {
      method: 'POST',
      body: { email: email.value, password: password.value },
    })
    if (data.data.mfaRequired) {
      // 方式情報を 2fa-verify へ引き継ぐ（AC-53）。欠落時は 2fa-verify 側が TOTP にフォールバックする。
      const mfaQuery = new URLSearchParams({ session: data.data.mfaSessionToken ?? '' })
      if (data.data.primaryMethod) mfaQuery.set('primary', data.data.primaryMethod)
      if (data.data.availableMethods?.length) {
        mfaQuery.set('methods', data.data.availableMethods.join(','))
      }
      // ログイン後の遷移先指定（redirect）も 2FA 通過後まで保持する。
      if (typeof route.query.redirect === 'string') mfaQuery.set('redirect', route.query.redirect)
      navigationTarget = `/2fa-verify?${mfaQuery.toString()}`
    } else {
      navigationTarget = await completeLogin(data.data, data.data)
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
    passwordPending = false
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
    passwordPending = false
  }
}
</script>

<template>
  <form
    id="login-form"
    :data-pre-hydration-submit="loading ? 'pending' : undefined"
    @submit.prevent="handleLogin"
  >
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

      <div class="flex flex-col gap-2">
        <label for="email">{{ t('auth.login.email') }}</label>
        <InputText
          id="email"
          v-model="email"
          type="email"
          placeholder="example@mannschaft.app"
          required
          data-allow-mismatch
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
          data-allow-mismatch
          :input-props="passwordInputProps"
        />
      </div>
      <Button
        type="submit"
        :label="t('auth.login.submit')"
        icon="pi pi-sign-in"
        loading-icon="pi pi-spinner pi-spin"
        :loading="loading"
        :disabled="busy"
        :aria-busy="loading || undefined"
        data-login-submit
        class="mt-2 min-h-11"
      />
      <div class="flex flex-col items-center gap-2">
        <NuxtLink to="/forgot-password" class="text-sm text-primary hover:underline">
          {{ t('auth.login.forgot_password') }}
        </NuxtLink>
        <NuxtLink to="/register" class="text-sm text-primary hover:underline">
          {{ t('auth.register.title') }}
        </NuxtLink>
      </div>
      <!-- セパレーター -->
      <div class="flex items-center gap-3">
        <div class="flex-1 border-t border-surface-200 dark:border-surface-600" />
        <span class="text-sm text-surface-400">{{ $t('auth.oauth.or') }}</span>
        <div class="flex-1 border-t border-surface-200 dark:border-surface-600" />
      </div>
      <Button
        type="button"
        :label="t('auth.login.passkey_submit')"
        icon="pi pi-key"
        severity="secondary"
        outlined
        class="min-h-11 w-full"
        :loading="passkeyLoading"
        :disabled="busy"
        :aria-busy="passkeyLoading || undefined"
        data-passkey-login
        @click="handlePasskeyLogin"
      />
      <!-- Google ログインボタン -->
      <Button
        type="button"
        :label="$t('auth.oauth.google_login')"
        icon="pi pi-google"
        severity="secondary"
        outlined
        class="min-h-11 w-full"
        :loading="googleLoading"
        :disabled="busy"
        @click="loginWithGoogle"
      />


    </div>
  </form>
</template>
