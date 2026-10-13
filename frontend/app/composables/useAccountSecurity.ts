import type { components } from '~/types/generated'
import type { AuthSessionResponse, WebAuthnCredentialResponse } from '~/types/auth'
import type { LoginHistoryResponse } from '~/types/user-settings'

export function useAccountSecurity() {
  const notification = useNotification()
  const { t } = useI18n()
  const {
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

  async function handleRevokeSession(id: number) {
    try {
      await revokeSession(id)
      sessions.value = sessions.value.filter((s) => s.id !== id)
      notification.success(t('settings.security.toast.revoke_success'))
    } catch {
      notification.error(t('settings.security.toast.revoke_error'))
    }
  }

  async function handleRevokeAllSessions() {
    try {
      await revokeAllSessions()
      sessions.value = []
      notification.success(t('settings.security.toast.revoke_all_success'))
    } catch {
      notification.error(t('settings.security.toast.revoke_all_error'))
    }
  }

  async function handleDeleteCredential(id: number) {
    try {
      await deleteWebAuthnCredential(id)
      credentials.value = credentials.value.filter((c) => c.id !== id)
      notification.success(t('settings.security.toast.delete_key_success'))
    } catch {
      notification.error(t('settings.security.toast.delete_key_error'))
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
      notification.success(t('settings.security.toast.rename_success'))
    } catch {
      notification.error(t('settings.security.toast.rename_error'))
    }
  }

  return {
    sessions,
    credentials,
    renameDialog,
    renameTarget,
    newDeviceName,
    loginHistory,
    loginHistoryNextCursor,
    loginHistoryHasNext,
    loadingMoreHistory,
    loadSecurity,
    loadLoginHistory,
    handleRevokeSession,
    handleRevokeAllSessions,
    handleDeleteCredential,
    openRenameDialog,
    handleRenameCredential,
  }
}

// ============================================================
// 2段階認証（2FA）設定（CMP-261012-0910 丁隊 AC-2〜5, 29, 34, 44）
// ============================================================

type Schemas = components['schemas']
type TwoFactorStatus = Schemas['TwoFactorStatusResponse']
type MfaEmailSend = Schemas['MfaEmailSendResponse']

/** 本人確認に使える要素。 */
export type TwoFactorFactor = 'TOTP' | 'BACKUP_CODE' | 'EMAIL'

/**
 * 無効化・バックアップコード再生成で BE へ渡す本人確認情報。
 * 無効化（AC-30）は要素が必須（パスワード保持者はパスワードも）。再生成（AC-44）はパスワードか要素のどちらかでよく、
 * 提示しなかった側は undefined のまま送らない（示したものはすべて BE で照合される）。
 */
export interface TwoFactorReauthInput {
  method?: TwoFactorFactor
  code?: string
  /** 入力した場合のみ。パスワードを持たない利用者（OAuth のみ等）は常に undefined。 */
  currentPassword?: string
}

interface ApiErrorShape {
  statusCode?: number
  status?: number
  response?: { status?: number; headers?: { get?: (name: string) => string | null } }
  data?: { error?: { code?: string } }
}

/** エラーを i18n キー（settings.security.two_fa.error.*）と補間値へ写像した結果。 */
export interface TwoFactorErrorInfo {
  key: string
  params?: Record<string, unknown>
  code?: string
}

const ERROR_KEY_BY_CODE: Record<string, string> = {
  AUTH_074: 'invalid_code',
  AUTH_076: 'resend_wait',
  AUTH_077: 'send_limit',
  AUTH_078: 'already_enabled',
  AUTH_079: 'not_enabled',
  AUTH_080: 'reauth_failed',
  AUTH_081: 'email_missing',
  AUTH_082: 'email_unavailable',
}

/**
 * API エラーを表示用の i18n キーへ写像する。429 は Retry-After の秒数を補間値に含める。
 * 未知のエラーは握りつぶさず generic に倒す（呼び出し側が必ず利用者へ見せる）。
 */
export function describeTwoFactorError(e: unknown): TwoFactorErrorInfo {
  const err = (e ?? {}) as ApiErrorShape
  const status = err.response?.status ?? err.statusCode ?? err.status
  const code = err.data?.error?.code
  if (status === 429) {
    const raw = err.response?.headers?.get?.('Retry-After') ?? null
    const seconds = raw === null ? Number.NaN : Number.parseInt(raw, 10)
    return Number.isFinite(seconds) && seconds > 0
      ? { key: 'rate_limited', params: { seconds }, code }
      : { key: 'rate_limited_unknown', code }
  }
  const mapped = code ? ERROR_KEY_BY_CODE[code] : undefined
  return { key: mapped ?? 'generic', code }
}

export function useTwoFactorSettings() {
  const api = useApi()
  const notification = useNotification()
  const { t } = useI18n()

  const status = ref<TwoFactorStatus | null>(null)
  const loadingStatus = ref(false)
  const busy = ref(false)
  /** 直近の失敗を利用者へ見せるための文言（成功・キャンセル・次の操作開始でクリアする）。 */
  const errorMessage = ref('')
  /** BE のフラグが off（AUTH_082）と判明したら false。メール方式の開始ボタンを無効にする。 */
  const emailAvailable = ref(true)

  const totpSetup = ref<{ secret: string; qrCodeUrl: string } | null>(null)
  const emailSetup = ref<MfaEmailSend | null>(null)
  const reauthEmail = ref<MfaEmailSend | null>(null)

  const backupCodes = ref<string[]>([])
  const showBackupCodesDialog = ref(false)

  function fail(e: unknown) {
    const info = describeTwoFactorError(e)
    if (info.code === 'AUTH_082') emailAvailable.value = false
    errorMessage.value = t(`settings.security.two_fa.error.${info.key}`, info.params ?? {})
  }

  async function loadStatus(): Promise<void> {
    loadingStatus.value = true
    try {
      status.value = (await api<{ data: TwoFactorStatus }>('/api/v1/auth/2fa/status')).data
    } catch (e) {
      fail(e)
      notification.error(t('settings.security.two_fa.error.status_load'), errorMessage.value)
    } finally {
      loadingStatus.value = false
    }
  }

  /** 共通: busy 管理とエラー表示つきで API 呼び出しを実行し、成功時に結果を返す（失敗は null）。 */
  async function run<T>(action: () => Promise<T>): Promise<T | null> {
    busy.value = true
    errorMessage.value = ''
    try {
      return await action()
    } catch (e) {
      fail(e)
      return null
    } finally {
      busy.value = false
    }
  }

  function showBackupCodes(codes: string[]) {
    backupCodes.value = codes
    showBackupCodesDialog.value = true
  }

  // === TOTP ===
  async function startTotpSetup(): Promise<void> {
    const res = await run(() =>
      api<{ data: { secret: string; qrCodeUrl: string } }>('/api/v1/auth/2fa/setup', { method: 'POST' }),
    )
    if (res) totpSetup.value = res.data
  }

  async function confirmTotpSetup(totpCode: string): Promise<boolean> {
    const res = await run(() =>
      api<{ data: Schemas['BackupCodesResponse'] }>('/api/v1/auth/2fa/verify', {
        method: 'POST',
        body: { totpCode },
      }),
    )
    if (!res) return false
    totpSetup.value = null
    showBackupCodes(res.data.backupCodes ?? [])
    notification.success(t('settings.security.two_fa.toast.enabled'))
    await loadStatus()
    return true
  }

  function cancelTotpSetup() {
    totpSetup.value = null
    errorMessage.value = ''
  }

  // === メール2FA ===
  async function startEmailSetup(): Promise<boolean> {
    const res = await run(() => api<{ data: MfaEmailSend }>('/api/v1/auth/2fa/email/setup', { method: 'POST' }))
    if (!res) return false
    emailSetup.value = res.data
    return true
  }

  async function confirmEmailSetup(code: string): Promise<boolean> {
    const res = await run(() =>
      api<{ data: Schemas['BackupCodesResponse'] }>('/api/v1/auth/2fa/email/enable', {
        method: 'POST',
        body: { code },
      }),
    )
    if (!res) return false
    emailSetup.value = null
    showBackupCodes(res.data.backupCodes ?? [])
    notification.success(t('settings.security.two_fa.toast.enabled'))
    await loadStatus()
    return true
  }

  function cancelEmailSetup() {
    emailSetup.value = null
    errorMessage.value = ''
  }

  // === 本人確認（無効化・再生成） ===
  async function sendReauthEmail(): Promise<boolean> {
    const res = await run(() =>
      api<{ data: MfaEmailSend }>('/api/v1/auth/2fa/reauth/email/send', { method: 'POST' }),
    )
    if (!res) return false
    reauthEmail.value = res.data
    return true
  }

  function resetReauth() {
    reauthEmail.value = null
    errorMessage.value = ''
  }

  function reauthBody(input: TwoFactorReauthInput) {
    return {
      ...(input.method && input.code ? { method: input.method, code: input.code } : {}),
      ...(input.currentPassword ? { currentPassword: input.currentPassword } : {}),
    }
  }

  async function disableTwoFactor(input: TwoFactorReauthInput): Promise<boolean> {
    const res = await run(() => api('/api/v1/auth/2fa/disable', { method: 'POST', body: reauthBody(input) }))
    if (res === null) return false
    notification.success(t('settings.security.two_fa.toast.disabled'))
    resetReauth()
    await loadStatus()
    return true
  }

  async function regenerateBackupCodes(input: TwoFactorReauthInput): Promise<boolean> {
    const res = await run(() =>
      api<{ data: Schemas['BackupCodesResponse'] }>('/api/v1/auth/2fa/backup-codes/regenerate', {
        method: 'POST',
        body: reauthBody(input),
      }),
    )
    if (!res) return false
    showBackupCodes(res.data.backupCodes ?? [])
    notification.success(t('settings.security.toast.regenerate_success'))
    resetReauth()
    await loadStatus()
    return true
  }

  function closeBackupCodesDialog() {
    showBackupCodesDialog.value = false
    backupCodes.value = []
  }

  return {
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
  }
}
