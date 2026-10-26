<script setup lang="ts">
import type { TwoFactorReauthInput } from '~/composables/useAccountSecurity'

/**
 * 2段階認証（2FA）設定セクション（security.vue / account.vue 共通）。
 *
 * 状態は GET /2fa/status から取る（AC-4/AC-5）。
 * 未設定 → TOTP またはメールで有効化、有効時 → バックアップコード再生成・無効化。
 */
defineProps<{
  /** パスワードを持つ利用者か。false なら本人確認でパスワード欄を出さない。 */
  hasPassword: boolean
}>()

const { t } = useI18n()
const notification = useNotification()

const {
  status,
  loadingStatus,
  busy,
  errorMessage,
  emailAvailable,
  totpSetup,
  emailSetup,
  reauthEmail,
  backupCodes,
  showBackupCodesDialog,
  loadStatus,
  startTotpSetup,
  confirmTotpSetup,
  cancelTotpSetup,
  startEmailSetup,
  confirmEmailSetup,
  cancelEmailSetup,
  sendReauthEmail,
  resetReauth,
  disableTwoFactor,
  regenerateBackupCodes,
  closeBackupCodesDialog,
} = useTwoFactorSettings()

const totpCode = ref('')
const emailCode = ref('')
const reauthMode = ref<'disable' | 'regenerate' | null>(null)
const resendRemaining = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

/** 残数がこの値以下なら再生成を促す。 */
const LOW_BACKUP_CODES = 2

const isEnabled = computed(() => status.value?.enabled === true)
const lowBackupCodes = computed(
  () => isEnabled.value && (status.value?.backupCodesRemaining ?? 0) <= LOW_BACKUP_CODES,
)
const setupInProgress = computed(() => totpSetup.value !== null || emailSetup.value !== null)

onMounted(loadStatus)
onUnmounted(() => {
  if (timer) clearInterval(timer)
})

function startCountdown(seconds: number) {
  if (timer) clearInterval(timer)
  timer = null
  resendRemaining.value = seconds
  if (seconds <= 0) return
  timer = setInterval(() => {
    resendRemaining.value -= 1
    if (resendRemaining.value <= 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

async function onStartTotp() {
  totpCode.value = ''
  await startTotpSetup()
}

async function onConfirmTotp() {
  if (await confirmTotpSetup(totpCode.value)) totpCode.value = ''
}

function onCancelTotp() {
  totpCode.value = ''
  cancelTotpSetup()
}

async function onStartEmail() {
  emailCode.value = ''
  if (await startEmailSetup()) startCountdown(emailSetup.value?.resendAfterSeconds ?? 0)
}

async function onConfirmEmail() {
  if (await confirmEmailSetup(emailCode.value)) emailCode.value = ''
}

function onCancelEmail() {
  emailCode.value = ''
  cancelEmailSetup()
}

async function copySecret() {
  try {
    await navigator.clipboard.writeText(totpSetup.value?.secret ?? '')
    notification.success(t('settings.security.two_fa.totp.secret_copied'))
  } catch {
    notification.error(t('settings.security.two_fa.backup.copy_failed'))
  }
}

function openReauth(mode: 'disable' | 'regenerate') {
  resetReauth()
  reauthMode.value = mode
}

function closeReauth() {
  reauthMode.value = null
  resetReauth()
}

async function onReauthSubmit(input: TwoFactorReauthInput) {
  const ok =
    reauthMode.value === 'disable'
      ? await disableTwoFactor(input)
      : await regenerateBackupCodes(input)
  if (ok) reauthMode.value = null
}
</script>

<template>
  <SectionCard :title="$t('settings.security.two_fa_section_title')">
    <div v-if="loadingStatus && !status" class="py-4 text-center text-sm text-surface-400">
      {{ $t('settings.security.two_fa.loading') }}
    </div>

    <!-- 状態取得失敗: 未設定と誤認させないため再試行を出す -->
    <div v-else-if="!status" class="space-y-3" data-testid="two-fa-status-error">
      <Message severity="error" :closable="false">
        {{ errorMessage || $t('settings.security.two_fa.error.status_load') }}
      </Message>
      <Button
        translate="no"
        :label="$t('settings.security.two_fa.retry_button')"
        icon="pi pi-refresh"
        severity="secondary"
        @click="loadStatus"
      />
    </div>

    <!-- 有効化済み -->
    <div v-else-if="isEnabled && !setupInProgress" class="space-y-4" data-testid="two-fa-enabled">
      <div class="flex flex-wrap items-center gap-2">
        <Tag
          :value="
            status?.method === 'EMAIL'
              ? $t('settings.security.two_fa.status_email')
              : $t('settings.security.two_fa.status_totp')
          "
          severity="success"
          data-testid="two-fa-status-tag"
        />
        <span class="text-sm text-surface-500" data-testid="two-fa-backup-remaining">
          {{ $t('settings.security.two_fa.backup_remaining', { count: status?.backupCodesRemaining ?? 0 }) }}
        </span>
      </div>
      <Message v-if="lowBackupCodes" severity="warn" :closable="false" data-testid="two-fa-backup-low">
        {{ $t('settings.security.two_fa.backup_low') }}
      </Message>
      <div class="flex flex-wrap gap-2">
        <Button
          translate="no"
          :label="$t('settings.security.regenerate_backup_codes_button')"
          icon="pi pi-refresh"
          severity="secondary"
          data-testid="two-fa-regenerate"
          @click="openReauth('regenerate')"
        />
        <Button
          translate="no"
          :label="$t('settings.security.two_fa.disable_button')"
          icon="pi pi-lock-open"
          severity="danger"
          outlined
          data-testid="two-fa-disable"
          @click="openReauth('disable')"
        />
      </div>
    </div>

    <!-- 未設定 -->
    <div v-else-if="!setupInProgress" class="space-y-4" data-testid="two-fa-disabled">
      <p class="text-sm text-surface-500">
        <Tag :value="$t('settings.security.two_fa.status_disabled')" severity="secondary" class="mr-2" />
        {{ $t('settings.security.two_fa_description') }}
      </p>
      <Message v-if="errorMessage" severity="error" :closable="false" data-testid="two-fa-error">
        {{ errorMessage }}
      </Message>
      <Message v-if="!emailAvailable" severity="info" :closable="false" data-testid="two-fa-email-unavailable">
        {{ $t('settings.security.two_fa.error.email_unavailable') }}
      </Message>
      <div class="flex flex-wrap gap-2">
        <Button
          translate="no"
          :label="$t('settings.security.setup_2fa_button')"
          icon="pi pi-shield"
          :loading="busy"
          data-testid="two-fa-setup-totp"
          @click="onStartTotp"
        />
        <Button
          translate="no"
          :label="$t('settings.security.two_fa.setup_email_button')"
          icon="pi pi-envelope"
          severity="secondary"
          :loading="busy"
          :disabled="!emailAvailable"
          data-testid="two-fa-setup-email"
          @click="onStartEmail"
        />
      </div>
    </div>

    <!-- TOTP セットアップ中: QR → 6桁入力 -->
    <form
      v-else-if="totpSetup"
      class="space-y-4"
      data-testid="two-fa-totp-setup"
      @submit.prevent="onConfirmTotp"
    >
      <p class="text-sm text-surface-500">{{ $t('settings.security.scan_qr_description') }}</p>
      <div class="flex justify-center" data-testid="two-fa-qr">
        <QrCodeImage :value="totpSetup.qrCodeUrl" :size="192" center-label="" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('settings.security.secret_key') }}</label>
        <div class="flex items-center gap-2">
          <code
            class="block flex-1 break-all rounded bg-surface-100 px-3 py-2 text-sm dark:bg-surface-700"
            data-testid="two-fa-secret"
          >{{ totpSetup.secret }}</code>
          <Button
            type="button"
            icon="pi pi-copy"
            severity="secondary"
            text
            rounded
            :aria-label="$t('settings.security.two_fa.totp.copy_secret')"
            data-testid="two-fa-copy-secret"
            @click="copySecret"
          />
        </div>
      </div>
      <div class="flex flex-col gap-2">
        <label class="text-sm font-semibold">{{ $t('settings.security.two_fa.totp.code_label') }}</label>
        <InputOtp v-model="totpCode" :length="6" integer-only data-testid="two-fa-totp-code" />
      </div>
      <Message v-if="errorMessage" severity="error" :closable="false" data-testid="two-fa-error">
        {{ errorMessage }}
      </Message>
      <div class="flex flex-wrap gap-2">
        <Button
          type="submit"
          translate="no"
          :label="$t('settings.security.two_fa.enable_button')"
          icon="pi pi-check"
          :loading="busy"
          :disabled="totpCode.length !== 6"
          data-testid="two-fa-totp-enable"
        />
        <Button
          type="button"
          translate="no"
          :label="$t('button.cancel')"
          severity="secondary"
          :disabled="busy"
          data-testid="two-fa-totp-cancel"
          @click="onCancelTotp"
        />
      </div>
    </form>

    <!-- メール2FA セットアップ中: コード入力 -->
    <form
      v-else-if="emailSetup"
      class="space-y-4"
      data-testid="two-fa-email-setup"
      @submit.prevent="onConfirmEmail"
    >
      <p class="text-sm text-surface-500" data-testid="two-fa-email-sent">
        {{
          $t('settings.security.two_fa.email.sent', {
            email: emailSetup.maskedEmail ?? '',
            minutes: Math.max(1, Math.round((emailSetup.expiresInSeconds ?? 60) / 60)),
          })
        }}
      </p>
      <div class="flex flex-col gap-2">
        <label class="text-sm font-semibold">{{ $t('settings.security.two_fa.email.code_label') }}</label>
        <InputOtp v-model="emailCode" :length="6" integer-only data-testid="two-fa-email-code" />
      </div>
      <Message v-if="errorMessage" severity="error" :closable="false" data-testid="two-fa-error">
        {{ errorMessage }}
      </Message>
      <div class="flex flex-wrap gap-2">
        <Button
          type="submit"
          translate="no"
          :label="$t('settings.security.two_fa.enable_button')"
          icon="pi pi-check"
          :loading="busy"
          :disabled="emailCode.length !== 6"
          data-testid="two-fa-email-enable"
        />
        <Button
          type="button"
          translate="no"
          :label="
            resendRemaining > 0
              ? $t('settings.security.two_fa.email.resend_wait', { seconds: resendRemaining })
              : $t('settings.security.two_fa.email.resend_button')
          "
          icon="pi pi-refresh"
          severity="secondary"
          :disabled="busy || resendRemaining > 0"
          data-testid="two-fa-email-resend"
          @click="onStartEmail"
        />
        <Button
          type="button"
          translate="no"
          :label="$t('button.cancel')"
          severity="secondary"
          text
          :disabled="busy"
          data-testid="two-fa-email-cancel"
          @click="onCancelEmail"
        />
      </div>
    </form>
  </SectionCard>

  <SettingsTwoFactorReauthDialog
    :visible="reauthMode !== null"
    :mode="reauthMode ?? 'disable'"
    :has-password="hasPassword"
    :two-fa-method="status?.method"
    :busy="busy"
    :error-message="errorMessage"
    :reauth-email="reauthEmail"
    @submit="onReauthSubmit"
    @send-email="sendReauthEmail"
    @update:visible="(v: boolean) => { if (!v) closeReauth() }"
  />

  <SettingsBackupCodesDialog
    :visible="showBackupCodesDialog"
    :codes="backupCodes"
    @close="closeBackupCodesDialog"
  />
</template>
