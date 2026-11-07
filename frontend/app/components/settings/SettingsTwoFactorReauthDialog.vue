<script setup lang="ts">
import type { TwoFactorFactor, TwoFactorReauthInput } from '~/composables/useAccountSecurity'

/**
 * 2FA の無効化・バックアップコード再生成で使う本人確認ダイアログ（AC-30/AC-34/AC-44）。
 *
 * 本人確認の契約は mode ごとに BE と揃える。
 * - 無効化（disable・AC-30）: パスワードを持つ利用者はパスワードと要素（TOTP / 本人確認メールOTP /
 *   バックアップコード）の**両方**、パスワードを持たない利用者（OAuth のみ等）は要素のみを必須とする。
 * - 再生成（regenerate・AC-44）: パスワード**または**要素の**どちらか**でよい。示したものはすべて正しくなければ
 *   ならない（BE が照合する）ため、入力されたものだけを送る。途中まで入力したコードは送らせない。
 *   パスワードを持たない利用者は要素のみ。
 */
const props = defineProps<{
  visible: boolean
  mode: 'disable' | 'regenerate'
  hasPassword: boolean
  /** 現在有効な 2FA 方式。EMAIL 方式の利用者には TOTP を選ばせない。 */
  twoFaMethod?: 'TOTP' | 'EMAIL'
  busy: boolean
  errorMessage: string
  /** 本人確認メールOTP の送信結果（未送信なら null）。 */
  reauthEmail: { maskedEmail?: string; expiresInSeconds?: number; resendAfterSeconds?: number } | null
}>()

const emit = defineEmits<{
  submit: [input: TwoFactorReauthInput]
  sendEmail: []
  'update:visible': [value: boolean]
}>()

const { t } = useI18n()

const factor = ref<TwoFactorFactor>('TOTP')
const code = ref('')
const password = ref('')
const resendRemaining = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

const factorOptions = computed(() => {
  const options: { label: string; value: TwoFactorFactor }[] = []
  if (props.twoFaMethod !== 'EMAIL') {
    options.push({ label: t('settings.security.two_fa.reauth.method_totp'), value: 'TOTP' })
  }
  options.push(
    { label: t('settings.security.two_fa.reauth.method_email'), value: 'EMAIL' },
    { label: t('settings.security.two_fa.reauth.method_backup'), value: 'BACKUP_CODE' },
  )
  return options
})

const isOtpFactor = computed(() => factor.value !== 'BACKUP_CODE')
const normalizedCode = computed(() => (isOtpFactor.value ? code.value : code.value.replace(/[\s-]/g, '')))
const codeEntered = computed(() => normalizedCode.value.length > 0)
const codeReady = computed(() =>
  isOtpFactor.value ? /^\d{6}$/.test(code.value) : normalizedCode.value.length > 0,
)
const passwordEntered = computed(() => props.hasPassword && password.value.length > 0)
/** 本人確認として成立する入力が揃っているか（mode ごとの BE 契約）。 */
const reauthReady = computed(() => {
  if (props.mode === 'disable') {
    // AC-30: パスワード保持者はパスワード＋要素の両方
    return codeReady.value && (!props.hasPassword || passwordEntered.value)
  }
  // AC-44: パスワードまたは要素のどちらか。途中まで入力したコード（6 桁未満の OTP 等）は送らせない
  if (codeEntered.value && !codeReady.value) return false
  return codeReady.value || passwordEntered.value
})
const canSubmit = computed(() => reauthReady.value && !props.busy)
const description = computed(() => {
  if (props.mode === 'disable') return t('settings.security.two_fa.reauth.disable_description')
  return props.hasPassword
    ? t('settings.security.two_fa.reauth.regenerate_description')
    : t('settings.security.two_fa.reauth.regenerate_description_no_password')
})
const title = computed(() =>
  props.mode === 'disable'
    ? t('settings.security.two_fa.reauth.disable_title')
    : t('settings.security.two_fa.reauth.regenerate_title'),
)
const submitLabel = computed(() =>
  props.mode === 'disable'
    ? t('settings.security.two_fa.reauth.disable_submit')
    : t('settings.security.two_fa.reauth.regenerate_submit'),
)

function stopTimer() {
  if (timer) clearInterval(timer)
  timer = null
}

function startCountdown(seconds: number) {
  stopTimer()
  resendRemaining.value = seconds
  if (seconds <= 0) return
  timer = setInterval(() => {
    resendRemaining.value -= 1
    if (resendRemaining.value <= 0) stopTimer()
  }, 1000)
}

watch(
  () => props.visible,
  (v) => {
    if (!v) {
      stopTimer()
      return
    }
    factor.value = props.twoFaMethod === 'EMAIL' ? 'EMAIL' : 'TOTP'
    code.value = ''
    password.value = ''
    resendRemaining.value = 0
  },
  { immediate: true },
)

watch(
  () => props.reauthEmail,
  (mail) => {
    if (mail) startCountdown(mail.resendAfterSeconds ?? 0)
  },
)

// 要素を切り替えたらコードを消す（別要素のコードを誤送信しない）
watch(factor, () => {
  code.value = ''
})

onUnmounted(stopTimer)

function submit() {
  if (!canSubmit.value) return
  // 入力されたものだけを送る（再生成で未入力の要素・パスワードを「提示した」扱いにしない）
  emit('submit', {
    ...(codeReady.value ? { method: factor.value, code: normalizedCode.value } : {}),
    ...(passwordEntered.value ? { currentPassword: password.value } : {}),
  })
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="title"
    :modal="true"
    class="w-full max-w-md"
    data-testid="two-fa-reauth-dialog"
    @update:visible="emit('update:visible', $event)"
  >
    <form class="space-y-4" @submit.prevent="submit">
      <!-- エラーは本文の先頭に置く。末尾だと狭い画面でフッターの陰に隠れて利用者に見えない（ALIC-3）。 -->
      <Message v-if="errorMessage" severity="error" :closable="false" data-testid="two-fa-reauth-error">
        {{ errorMessage }}
      </Message>

      <p class="text-sm text-surface-500" data-testid="two-fa-reauth-description">
        {{ description }}
      </p>

      <div v-if="hasPassword" class="flex flex-col gap-2">
        <label for="twoFaReauthPassword" class="text-sm font-semibold">
          {{ $t('settings.security.two_fa.reauth.password_label') }}
        </label>
        <Password
          v-model="password"
          input-id="twoFaReauthPassword"
          :feedback="false"
          toggle-mask
          fluid
          data-testid="two-fa-reauth-password"
        />
      </div>

      <div class="flex flex-col gap-2">
        <span class="text-sm font-semibold">{{ $t('settings.security.two_fa.reauth.method_label') }}</span>
        <SelectButton
          v-model="factor"
          :options="factorOptions"
          option-label="label"
          option-value="value"
          :allow-empty="false"
          data-testid="two-fa-reauth-method"
        />
      </div>

      <div v-if="factor === 'EMAIL'" class="space-y-2">
        <Button
          type="button"
          translate="no"
          :label="
            resendRemaining > 0
              ? $t('settings.security.two_fa.email.resend_wait', { seconds: resendRemaining })
              : reauthEmail
                ? $t('settings.security.two_fa.email.resend_button')
                : $t('settings.security.two_fa.reauth.send_email_button')
          "
          icon="pi pi-envelope"
          severity="secondary"
          size="small"
          :disabled="busy || resendRemaining > 0"
          data-testid="two-fa-reauth-send-email"
          @click="emit('sendEmail')"
        />
        <p v-if="reauthEmail" class="text-xs text-surface-500" data-testid="two-fa-reauth-email-sent">
          {{
            $t('settings.security.two_fa.email.sent', {
              email: reauthEmail.maskedEmail ?? '',
              minutes: Math.max(1, Math.round((reauthEmail.expiresInSeconds ?? 60) / 60)),
            })
          }}
        </p>
      </div>

      <div class="flex flex-col gap-2">
        <label class="text-sm font-semibold">
          {{
            factor === 'BACKUP_CODE'
              ? $t('settings.security.two_fa.reauth.code_label_backup')
              : $t('settings.security.two_fa.reauth.code_label_otp')
          }}
        </label>
        <InputOtp v-if="isOtpFactor" :key="factor" v-model="code" :length="6" integer-only data-testid="two-fa-reauth-otp" />
        <InputText v-else v-model="code" autocomplete="off" fluid data-testid="two-fa-reauth-backup-input" />
      </div>

      <div class="flex justify-end gap-2">
        <Button
          type="button"
          translate="no"
          :label="$t('button.cancel')"
          severity="secondary"
          @click="emit('update:visible', false)"
        />
        <Button
          type="submit"
          translate="no"
          :label="submitLabel"
          :severity="mode === 'disable' ? 'danger' : 'primary'"
          :loading="busy"
          :disabled="!canSubmit"
          data-testid="two-fa-reauth-submit"
        />
      </div>
    </form>
  </Dialog>
</template>
