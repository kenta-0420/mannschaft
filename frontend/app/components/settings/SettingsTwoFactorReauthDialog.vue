<script setup lang="ts">
import type { TwoFactorFactor, TwoFactorReauthInput } from '~/composables/useAccountSecurity'

/**
 * 2FA の無効化・バックアップコード再生成で使う本人確認ダイアログ（AC-34/AC-44）。
 *
 * パスワードを持つ利用者はパスワードと要素（TOTP / 本人確認メールOTP / バックアップコード）の両方を求める。
 * パスワードを持たない利用者（OAuth のみ等）にはパスワード欄を出さず、要素のみを求める。
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
const codeReady = computed(() =>
  isOtpFactor.value ? /^\d{6}$/.test(code.value) : code.value.replace(/[\s-]/g, '').length > 0,
)
const canSubmit = computed(
  () => codeReady.value && (!props.hasPassword || password.value.length > 0) && !props.busy,
)
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
  emit('submit', {
    method: factor.value,
    code: isOtpFactor.value ? code.value : code.value.replace(/[\s-]/g, ''),
    currentPassword: props.hasPassword ? password.value : undefined,
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
      <p class="text-sm text-surface-500">
        {{ $t('settings.security.two_fa.reauth.description') }}
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

      <Message v-if="errorMessage" severity="error" :closable="false" data-testid="two-fa-reauth-error">
        {{ errorMessage }}
      </Message>

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
