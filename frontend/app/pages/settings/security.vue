<script setup lang="ts">
import type { AuthSessionResponse, WebAuthnCredentialResponse } from '~/types/auth'
import { useWebAuthnRegister } from '~/composables/useWebAuthnRegister'

definePageMeta({
  middleware: 'auth',
})

const notification = useNotification()
const authStore = useAuthStore()
const {
  setup2fa,
  regenerateBackupCodes,
  getSessions,
  revokeSession,
  revokeAllSessions,
  getWebAuthnCredentials,
  deleteWebAuthnCredential,
  updateWebAuthnCredential,
} = useAuthApi()

const loading = ref(true)
const sessions = ref<AuthSessionResponse[]>([])
const credentials = ref<WebAuthnCredentialResponse[]>([])

// 2FA state
const totpSetup = ref<{ secret: string; qrCodeUrl: string } | null>(null)
const backupCodes = ref<string[]>([])
const showBackupCodesDialog = ref(false)
const setting2fa = ref(false)
const regenerating = ref(false)

// WebAuthn rename
const renameDialog = ref(false)
const renameTarget = ref<WebAuthnCredentialResponse | null>(null)
const newDeviceName = ref('')

// WebAuthn register
const { registerPasskey, isRegistering: isRegisteringPasskey, error: registerError } = useWebAuthnRegister()
const registerDialog = ref(false)
const newPasskeyName = ref('')

// 使い方ガイド（モーダル）
const showHelp = ref(false)

const { t } = useI18n()

onMounted(async () => {
  await loadData()
})

async function loadData() {
  loading.value = true
  try {
    const [sessRes, credRes] = await Promise.all([getSessions(), getWebAuthnCredentials()])
    sessions.value = sessRes.data
    credentials.value = credRes.data
  } catch {
    notification.error('セキュリティ情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

// === 2FA ===
async function handleSetup2fa() {
  setting2fa.value = true
  try {
    const res = await setup2fa()
    totpSetup.value = res.data
  } catch {
    notification.error('2FAセットアップの開始に失敗しました')
  } finally {
    setting2fa.value = false
  }
}

async function handleRegenerateBackupCodes() {
  regenerating.value = true
  try {
    const res = await regenerateBackupCodes()
    backupCodes.value = res.data.backupCodes
    showBackupCodesDialog.value = true
    notification.success('バックアップコードを再生成しました')
  } catch {
    notification.error('バックアップコードの再生成に失敗しました')
  } finally {
    regenerating.value = false
  }
}

// === Sessions ===
async function handleRevokeSession(id: number) {
  try {
    await revokeSession(id)
    sessions.value = sessions.value.filter((s) => s.id !== id)
    notification.success('セッションを無効化しました')
  } catch {
    notification.error('セッションの無効化に失敗しました')
  }
}

async function handleRevokeAllSessions() {
  try {
    await revokeAllSessions()
    notification.success('全デバイスからログアウトしました')
    authStore.logout()
  } catch {
    notification.error('全デバイスログアウトに失敗しました')
  }
}

// === WebAuthn ===
async function handleDeleteCredential(id: number) {
  try {
    await deleteWebAuthnCredential(id)
    credentials.value = credentials.value.filter((c) => c.id !== id)
    notification.success('セキュリティキーを削除しました')
  } catch {
    notification.error('セキュリティキーの削除に失敗しました')
  }
}

function openRenameDialog(cred: WebAuthnCredentialResponse) {
  renameTarget.value = cred
  newDeviceName.value = cred.deviceName ?? ''
  renameDialog.value = true
}

async function handleRenameCredential() {
  if (!renameTarget.value) return
  try {
    const res = await updateWebAuthnCredential(renameTarget.value.id, {
      deviceName: newDeviceName.value,
    })
    const idx = credentials.value.findIndex((c) => c.id === renameTarget.value!.id)
    if (idx !== -1) credentials.value[idx] = res.data
    renameDialog.value = false
    notification.success('デバイス名を更新しました')
  } catch {
    notification.error('デバイス名の更新に失敗しました')
  }
}

async function handlePasskeyRegister() {
  const deviceName = newPasskeyName.value.trim() || undefined
  const success = await registerPasskey(deviceName)
  if (success) {
    registerDialog.value = false
    newPasskeyName.value = ''
    notification.success(t('settings.security.webauthn.register_success'))
    // 資格情報一覧を再取得
    try {
      const credRes = await getWebAuthnCredentials()
      credentials.value = credRes.data
    } catch {
      // 再取得失敗は非致命的。次回リロード時に反映される。
    }
  } else if (registerError.value) {
    notification.error(t('settings.security.webauthn.register_error'))
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader title="セキュリティ" back-to="/settings" help @help="showHelp = true" />

    <SecurityHelpDialog v-model:visible="showHelp" />

    <PageLoading v-if="loading" />

    <div v-else class="fade-in space-y-8">
      <SettingsSecuritySection
        :totp-setup="totpSetup"
        :setting2fa="setting2fa"
        :regenerating="regenerating"
        :sessions="sessions"
        :credentials="credentials"
        :backup-codes="backupCodes"
        :show-backup-codes-dialog="showBackupCodesDialog"
        :rename-dialog="renameDialog"
        :new-device-name="newDeviceName"
        :is-registering-passkey="isRegisteringPasskey"
        :register-dialog="registerDialog"
        :new-passkey-name="newPasskeyName"
        @setup2fa="handleSetup2fa"
        @regenerate-backup-codes="handleRegenerateBackupCodes"
        @revoke-session="handleRevokeSession"
        @revoke-all-sessions="handleRevokeAllSessions"
        @delete-credential="handleDeleteCredential"
        @open-rename-dialog="openRenameDialog"
        @rename-credential="handleRenameCredential"
        @passkey-register="handlePasskeyRegister"
        @update:show-backup-codes-dialog="showBackupCodesDialog = $event"
        @update:rename-dialog="renameDialog = $event"
        @update:new-device-name="newDeviceName = $event"
        @update:register-dialog="registerDialog = $event"
        @update:new-passkey-name="newPasskeyName = $event"
      />
    </div>
  </div>
</template>
