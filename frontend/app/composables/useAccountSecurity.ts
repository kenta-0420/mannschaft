import type { AuthSessionResponse, WebAuthnCredentialResponse } from '~/types/auth'
import type { LoginHistoryResponse } from '~/types/user-settings'

export function useAccountSecurity() {
  const notification = useNotification()
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
  const { getLoginHistory } = useUserSettingsApi()

  const sessions = ref<AuthSessionResponse[]>([])
  const credentials = ref<WebAuthnCredentialResponse[]>([])
  const totpSetup = ref<{ secret: string; qrCodeUrl: string } | null>(null)
  const backupCodes = ref<string[]>([])
  const showBackupCodesDialog = ref(false)
  const setting2fa = ref(false)
  const regenerating = ref(false)
  const renameDialog = ref(false)
  const renameTarget = ref<WebAuthnCredentialResponse | null>(null)
  const newDeviceName = ref('')

  const loginHistory = ref<LoginHistoryResponse[]>([])
  const loginHistoryNextCursor = ref<string | null>(null)
  const loginHistoryHasNext = ref(false)
  const loadingMoreHistory = ref(false)

  async function loadSecurity() {
    try {
      const [sessRes, credRes] = await Promise.all([getSessions(), getWebAuthnCredentials()])
      sessions.value = sessRes.data
      credentials.value = credRes.data
    } catch {
      /* silent */
    }
  }

  async function loadLoginHistory(cursor?: string) {
    if (cursor) loadingMoreHistory.value = true
    try {
      const res = await getLoginHistory(cursor, 10)
      if (cursor) loginHistory.value.push(...res.data)
      else loginHistory.value = res.data
      loginHistoryNextCursor.value = res.meta.nextCursor
      loginHistoryHasNext.value = res.meta.hasNext
    } catch {
      /* silent */
    } finally {
      loadingMoreHistory.value = false
    }
  }

  async function handleSetup2fa() {
    setting2fa.value = true
    try {
      totpSetup.value = (await setup2fa()).data
    } catch {
      notification.error('2FAセットアップの開始に失敗しました')
    } finally {
      setting2fa.value = false
    }
  }

  async function handleRegenerateBackupCodes() {
    regenerating.value = true
    try {
      backupCodes.value = (await regenerateBackupCodes()).data.backupCodes
      showBackupCodesDialog.value = true
      notification.success('バックアップコードを再生成しました')
    } catch {
      notification.error('バックアップコードの再生成に失敗しました')
    } finally {
      regenerating.value = false
    }
  }

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
      sessions.value = []
      notification.success('全デバイスからログアウトしました')
    } catch {
      notification.error('全デバイスログアウトに失敗しました')
    }
  }

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
    newDeviceName.value = cred.deviceName
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

  return {
    sessions,
    credentials,
    totpSetup,
    backupCodes,
    showBackupCodesDialog,
    setting2fa,
    regenerating,
    renameDialog,
    renameTarget,
    newDeviceName,
    loginHistory,
    loginHistoryNextCursor,
    loginHistoryHasNext,
    loadingMoreHistory,
    loadSecurity,
    loadLoginHistory,
    handleSetup2fa,
    handleRegenerateBackupCodes,
    handleRevokeSession,
    handleRevokeAllSessions,
    handleDeleteCredential,
    openRenameDialog,
    handleRenameCredential,
  }
}
