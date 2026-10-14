<script setup lang="ts">
import type { MfaVerificationMethod } from '~/composables/useAuthApi'

definePageMeta({
  layout: 'auth',
})

const route = useRoute()
const api = useApi()
const authStore = useAuthStore()
// 先回りリフレッシュ武装用に runtimeConfig を setup コンテキストで capture する（armProactiveRefresh 参照）。
const runtimeConfig = useRuntimeConfig()
const notification = useNotification()
const { applyAccountLocale } = useLocale()
const { t } = useI18n()
const { validateMfaLogin, sendLoginEmailOtp } = useAuthApi()

const KNOWN_METHODS: readonly MfaVerificationMethod[] = ['TOTP', 'EMAIL', 'BACKUP_CODE']
const TOTP_LENGTH = 6
const BACKUP_CODE_LENGTH = 8
// 残り枚数がこの値以下なら再生成を促す（AC-13）。
const BACKUP_CODES_LOW_THRESHOLD = 2

function isKnownMethod(value: string): value is MfaVerificationMethod {
  return (KNOWN_METHODS as readonly string[]).includes(value)
}

// ハイドレーション前に入力された値（パスワードマネージャによる TOTP 自動入力を含む）を取り込む。
// InputOtp は桁ごとに id を持たない input を並べて描画するため、セレクタで連結して読む。
// ref('') のままだとハイドレーション時に空で上書きされて消える。必ずセットアップ時に読むこと。
const totpCode = ref(readPrefilledInputGroupValue('.p-inputotp-input'))
const emailCode = ref('')
const backupCode = ref('')
const loading = ref(false)

// SSR 配信済み HTML に @submit.prevent が未結合の窓で送信ボタンを押されると、
// ブラウザ標準のフォーム送信が走って入力が失われるため、ハイドレーション完了まで送信を封じる。
const hydrated = useHydrated()
// ハイドレーション待ちの間もボタンをローディング表示にする（無反応に見える問題の解消）。
// :disabled="!hydrated" は Enter キーによる implicit submission 抑止のため別途維持する
// （PrimeVue の loading は内部的に disabled 相当になるが、明示指定で確実に塞ぐ）。
const submitting = computed(() => loading.value || !hydrated.value)

const mfaSessionToken = computed(() => route.query.session as string | undefined)

/** login.vue が引き継ぐ「このログインで提示できる要素」。欠落時（旧 BE 応答）は TOTP とバックアップコード。 */
const availableMethods = computed<MfaVerificationMethod[]>(() => {
  const raw = typeof route.query.methods === 'string' ? route.query.methods : ''
  const parsed = raw.split(',').filter(isKnownMethod)
  return parsed.length > 0 ? parsed : ['TOTP', 'BACKUP_CODE']
})

const primaryMethod = computed<MfaVerificationMethod>(() => {
  const raw = typeof route.query.primary === 'string' ? route.query.primary : ''
  return isKnownMethod(raw) && availableMethods.value.includes(raw) ? raw : availableMethods.value[0]!
})

const selectedMethod = ref<MfaVerificationMethod>(primaryMethod.value)

const METHOD_LABEL_KEYS: Record<MfaVerificationMethod, string> = {
  TOTP: 'auth.mfa.method_totp',
  EMAIL: 'auth.mfa.method_email',
  BACKUP_CODE: 'auth.mfa.method_backup_code',
}
const methodOptions = computed(() =>
  availableMethods.value.map((method) => ({ value: method, label: t(METHOD_LABEL_KEYS[method]) })),
)

/** 2FA 通過後の遷移先。オープンリダイレクトを避けるためサイト内パスだけ受ける。 */
const redirectTarget = computed(() => {
  const raw = route.query.redirect
  return typeof raw === 'string' && raw.startsWith('/') && !raw.startsWith('//') ? raw : '/'
})

/** セッション切れ・試行上限。以降このページでは続行できず、ログインからやり直す。 */
const sessionEndedReason = ref<'expired' | 'attempts' | null>(null)
const inlineError = ref('')

onMounted(() => {
  if (!mfaSessionToken.value) {
    navigateTo('/login')
  }
})

// --- メールOTP: 送信状態とカウントダウン ---
const maskedEmail = ref('')
const emailSent = ref(false)
const sendingEmail = ref(false)
const now = ref(Date.now())
const resendAvailableAt = ref(0)
const emailExpiresAt = ref(0)
let ticker: ReturnType<typeof setInterval> | null = null

function startTicker() {
  if (ticker) return
  ticker = setInterval(() => {
    now.value = Date.now()
  }, 1000)
}

onBeforeUnmount(() => {
  if (ticker) clearInterval(ticker)
})

const resendRemaining = computed(() =>
  Math.max(0, Math.ceil((resendAvailableAt.value - now.value) / 1000)),
)
const expiresRemaining = computed(() =>
  Math.max(0, Math.ceil((emailExpiresAt.value - now.value) / 1000)),
)
const emailExpired = computed(() => emailSent.value && expiresRemaining.value === 0)

function formatMinutesSeconds(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

interface ApiErrorShape {
  statusCode?: number
  response?: { status?: number; headers?: Headers }
  data?: { error?: { code?: string } }
}

function errorCodeOf(e: unknown): string | undefined {
  return (e as ApiErrorShape)?.data?.error?.code
}

function statusOf(e: unknown): number | undefined {
  const err = e as ApiErrorShape
  return err?.response?.status ?? err?.statusCode
}

/** セッション切れ・試行上限ならログインへ戻す状態にして true を返す。 */
function applySessionEnd(code: string | undefined): boolean {
  if (code === 'AUTH_075') {
    sessionEndedReason.value = 'attempts'
    return true
  }
  if (code === 'AUTH_073') {
    sessionEndedReason.value = 'expired'
    return true
  }
  return false
}

async function handleSendEmail() {
  if (!mfaSessionToken.value || sendingEmail.value || resendRemaining.value > 0) return

  sendingEmail.value = true
  inlineError.value = ''
  try {
    const res = await sendLoginEmailOtp(mfaSessionToken.value)
    const sentAt = Date.now()
    maskedEmail.value = res.data.maskedEmail ?? ''
    resendAvailableAt.value = sentAt + (res.data.resendAfterSeconds ?? 0) * 1000
    emailExpiresAt.value = sentAt + (res.data.expiresInSeconds ?? 0) * 1000
    now.value = sentAt
    emailSent.value = true
    emailCode.value = ''
    startTicker()
    notification.success(t('auth.mfa.email_sent'))
  } catch (e: unknown) {
    const code = errorCodeOf(e)
    if (applySessionEnd(code)) return
    if (statusOf(e) === 429) {
      // 再送待ち(AUTH_076)は Retry-After 秒だけ待たせる。送信回数上限(AUTH_077)は文言で伝える。
      const retryAfter = parseRetryAfterSeconds(
        (e as ApiErrorShape).response?.headers?.get('Retry-After') ?? null,
      )
      if (retryAfter !== null) {
        resendAvailableAt.value = Date.now() + retryAfter * 1000
        now.value = Date.now()
        startTicker()
      }
      inlineError.value
        = code === 'AUTH_077' ? t('auth.mfa.error_send_limit') : t('auth.mfa.error_resend_wait')
    } else if (code === 'AUTH_003') {
      inlineError.value = t('auth.login.account_locked')
    } else {
      inlineError.value = t('auth.mfa.error_send_failed')
    }
  } finally {
    sendingEmail.value = false
  }
}

// --- 入力 ---
/** バックアップコードの空白・ハイフンを除去する（AC-13）。BE は 8 桁の数字のみ受ける。 */
function normalizeBackupCode(value: string): string {
  return value.replace(/[\s\-‐-―－ー]/g, '')
}

const currentCode = computed(() => {
  if (selectedMethod.value === 'BACKUP_CODE') return normalizeBackupCode(backupCode.value)
  return selectedMethod.value === 'EMAIL' ? emailCode.value : totpCode.value
})
const requiredLength = computed(() =>
  selectedMethod.value === 'BACKUP_CODE' ? BACKUP_CODE_LENGTH : TOTP_LENGTH,
)
const canSubmit = computed(
  () =>
    sessionEndedReason.value === null
    && currentCode.value.length === requiredLength.value
    && (selectedMethod.value !== 'EMAIL' || (emailSent.value && !emailExpired.value)),
)

const backupInputRef = ref<{ $el?: HTMLElement } | null>(null)

watch(selectedMethod, async (method) => {
  inlineError.value = ''
  // バックアップコードへ切り替えたら、そのまま打てるよう入力欄へフォーカスを移す。
  if (method === 'BACKUP_CODE') {
    await nextTick()
    backupInputRef.value?.$el?.focus?.()
  }
})

function clearCurrentCode() {
  if (selectedMethod.value === 'BACKUP_CODE') backupCode.value = ''
  else if (selectedMethod.value === 'EMAIL') emailCode.value = ''
  else totpCode.value = ''
}

async function handleVerify() {
  if (!mfaSessionToken.value || !canSubmit.value || loading.value) return

  loading.value = true
  inlineError.value = ''
  const method = selectedMethod.value
  try {
    const data = await validateMfaLogin({
      mfaSessionToken: mfaSessionToken.value,
      method,
      code: currentCode.value,
    })
    authStore.setTokens(data.data.accessToken ?? '', data.data.refreshToken ?? '')
    // 2FA 検証成功直後に先回りリフレッシュタイマーを武装する。
    armProactiveRefresh(runtimeConfig, authStore)

    // 検証レスポンスにユーザー情報は無い。フルプロフィール（timezone・locale 等）を取得して store を更新する。
    // 検証自体は成功しているため、取得失敗は「コード不正」と混同せず別文言で伝えて遷移は続ける。
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
        }
      }>('/api/v1/users/me')
      await authStore.setUser({
        id: profile.data.id,
        email: profile.data.email,
        fullName: profile.data.lastName + ' ' + profile.data.firstName,
        profileImageUrl: profile.data.avatarUrl,
        systemRole: profile.data.systemRole ?? undefined,
        timezone: profile.data.timezone ?? undefined,
      })
      if (profile.data.locale) {
        await applyAccountLocale(profile.data.locale)
      }
    } catch {
      notification.error(t('auth.mfa.error_profile_failed'))
    }

    // ログイン成立直後にアカウント設定（外観・ナビ）をサーバーから同期する。
    // 新ブラウザ（シークレット等）で localStorage が空でも BEの保存済み設定が反映される。
    authStore.syncAccountSettings()

    // バックアップコードで入った場合は残り枚数を伝える。少なければ再生成を促す（AC-13）。
    if (method === 'BACKUP_CODE' && typeof data.data.backupCodesRemaining === 'number') {
      const remaining = data.data.backupCodesRemaining
      if (remaining <= BACKUP_CODES_LOW_THRESHOLD) {
        notification.warn(
          t('auth.mfa.backup_remaining_title', { count: remaining }),
          t('auth.mfa.backup_remaining_regenerate'),
        )
      } else {
        notification.info(t('auth.mfa.backup_remaining_title', { count: remaining }))
      }
    }

    navigateTo(redirectTarget.value)
  } catch (e: unknown) {
    const code = errorCodeOf(e)
    if (applySessionEnd(code)) return
    if (code === 'AUTH_003') {
      inlineError.value = t('auth.login.account_locked')
    } else if (statusOf(e) === 400 && code !== 'AUTH_074') {
      // 形式違反（桁数・数字以外）。試行回数には計上されない。
      inlineError.value = t('auth.mfa.error_format')
    } else {
      inlineError.value = t('auth.mfa.error_invalid_code')
    }
    clearCurrentCode()
  } finally {
    loading.value = false
  }
}

const subtitleKey = computed(() => {
  // メール方式は送信前にコード入力欄が無い。次の一手（送信ボタン）を案内する。
  if (selectedMethod.value === 'EMAIL' && !emailSent.value) return 'auth.mfa.subtitle_email_unsent'
  return {
    TOTP: 'auth.mfa.subtitle_totp',
    EMAIL: 'auth.mfa.subtitle_email',
    BACKUP_CODE: 'auth.mfa.subtitle_backup_code',
  }[selectedMethod.value]
})

// リカバリーリンクはセッションを引き継ぐ（AC-38）。引き継がないと 2fa-recovery が /login へ弾き返す。
const recoveryLink = computed(() => ({
  path: '/2fa-recovery',
  query: {
    session: mfaSessionToken.value,
    primary: typeof route.query.primary === 'string' ? route.query.primary : undefined,
    methods: typeof route.query.methods === 'string' ? route.query.methods : undefined,
    redirect: typeof route.query.redirect === 'string' ? route.query.redirect : undefined,
  },
}))
</script>

<template>
  <form @submit.prevent="handleVerify">
    <div class="flex flex-col items-center gap-6">
      <div class="text-center">
        <h2 class="text-xl font-semibold mb-2">
          {{ t('auth.mfa.title') }}
        </h2>
        <p class="text-sm text-surface-500">
          {{ t(subtitleKey) }}
        </p>
      </div>

      <!-- セッション切れ・試行上限: 続行できないのでログインへ戻す -->
      <div
        v-if="sessionEndedReason"
        class="flex w-full flex-col items-center gap-3 text-center"
        role="alert"
        data-testid="mfa-session-ended"
      >
        <Message severity="error" :closable="false" class="w-full">
          {{
            sessionEndedReason === 'attempts'
              ? t('auth.mfa.error_attempts_exceeded')
              : t('auth.mfa.error_session_expired')
          }}
        </Message>
        <Button
          as="a"
          href="/login"
          :label="t('auth.mfa.back_to_login_action')"
          icon="pi pi-sign-in"
          class="w-full"
          @click.prevent="navigateTo('/login')"
        />
      </div>

      <template v-else>
        <SelectButton
          v-if="methodOptions.length > 1"
          v-model="selectedMethod"
          :options="methodOptions"
          option-label="label"
          option-value="value"
          :allow-empty="false"
          :aria-label="t('auth.mfa.method_switch')"
          data-testid="mfa-method-switch"
        />

        <!-- TOTP -->
        <InputOtp
          v-if="selectedMethod === 'TOTP'"
          v-model="totpCode"
          :length="TOTP_LENGTH"
          integer-only
        />

        <!-- メールOTP -->
        <template v-else-if="selectedMethod === 'EMAIL'">
          <div class="flex w-full flex-col items-center gap-3">
            <Button
              type="button"
              :label="emailSent ? t('auth.mfa.email_resend') : t('auth.mfa.email_send')"
              icon="pi pi-envelope"
              severity="secondary"
              outlined
              :loading="sendingEmail"
              :disabled="!hydrated || resendRemaining > 0"
              class="w-full"
              data-testid="mfa-email-send"
              @click="handleSendEmail"
            />
            <p v-if="resendRemaining > 0" class="text-xs text-surface-500" data-testid="mfa-email-resend-wait">
              {{ t('auth.mfa.email_resend_wait', { seconds: resendRemaining }) }}
            </p>
            <template v-if="emailSent">
              <p class="text-center text-sm text-surface-500">
                {{ t('auth.mfa.email_sent_to', { email: maskedEmail }) }}
              </p>
              <p
                class="text-xs"
                :class="emailExpired ? 'text-red-500' : 'text-surface-500'"
                data-testid="mfa-email-expiry"
              >
                {{
                  emailExpired
                    ? t('auth.mfa.email_expired')
                    : t('auth.mfa.email_expires_in', { time: formatMinutesSeconds(expiresRemaining) })
                }}
              </p>
              <InputOtp v-model="emailCode" :length="TOTP_LENGTH" integer-only />
            </template>
          </div>
        </template>

        <!-- バックアップコード -->
        <div v-else class="flex w-full flex-col gap-2">
          <InputText
            ref="backupInputRef"
            v-model="backupCode"
            inputmode="numeric"
            autocomplete="one-time-code"
            :placeholder="t('auth.mfa.backup_code_placeholder')"
            :aria-label="t('auth.mfa.method_backup_code')"
            class="w-full text-center tracking-widest"
            data-testid="mfa-backup-input"
          />
          <p class="text-center text-xs text-surface-500">
            {{ t('auth.mfa.backup_code_hint') }}
          </p>
        </div>

        <p v-if="inlineError" class="text-sm text-red-500" role="alert">
          {{ inlineError }}
        </p>

        <Button
          type="submit"
          :label="t('auth.mfa.submit')"
          icon="pi pi-shield"
          :loading="submitting"
          :disabled="!hydrated || !canSubmit"
          class="w-full"
        />
      </template>

      <div class="flex flex-col items-center gap-2 text-sm">
        <NuxtLink :to="recoveryLink" class="text-primary hover:underline">
          {{ t('auth.mfa.use_recovery') }}
        </NuxtLink>
        <NuxtLink to="/login" class="text-primary hover:underline">
          {{ t('auth.mfa.back_to_login') }}
        </NuxtLink>
      </div>
    </div>
  </form>
</template>
