import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { describeTwoFactorError, useTwoFactorSettings } from './useAccountSecurity'

const apiMock = vi.fn()
const notificationMock = { success: vi.fn(), error: vi.fn() }

mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useNotification', () => () => notificationMock)
mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) => (params && Object.keys(params).length ? `${key}:${JSON.stringify(params)}` : key),
}))

function apiError(status: number, code?: string, retryAfter?: string) {
  return {
    response: { status, headers: { get: (n: string) => (n === 'Retry-After' ? (retryAfter ?? null) : null) } },
    data: code ? { error: { code } } : undefined,
  }
}

const ENABLED = { data: { enabled: true, method: 'TOTP', backupCodesRemaining: 8 } }

describe('describeTwoFactorError', () => {
  it('429 は Retry-After の秒数を補間値に含める', () => {
    expect(describeTwoFactorError(apiError(429, undefined, '42'))).toMatchObject({ key: 'rate_limited', params: { seconds: 42 } })
  })
  it('429 で Retry-After が無ければ秒数なしの文言', () => {
    expect(describeTwoFactorError(apiError(429)).key).toBe('rate_limited_unknown')
  })
  it('エラーコードを個別キーへ写像し、未知は generic', () => {
    expect(describeTwoFactorError(apiError(400, 'AUTH_074')).key).toBe('invalid_code')
    expect(describeTwoFactorError(apiError(400, 'AUTH_080')).key).toBe('reauth_failed')
    expect(describeTwoFactorError(apiError(400, 'AUTH_082')).key).toBe('email_unavailable')
    expect(describeTwoFactorError(apiError(500)).key).toBe('generic')
  })
})

describe('useTwoFactorSettings', () => {
  beforeEach(() => {
    apiMock.mockReset()
    notificationMock.success.mockReset()
    notificationMock.error.mockReset()
  })

  it('AC-5: status を GET /2fa/status から取得する', async () => {
    apiMock.mockResolvedValueOnce(ENABLED)
    const tf = useTwoFactorSettings()
    await tf.loadStatus()
    expect(apiMock).toHaveBeenCalledWith('/api/v1/auth/2fa/status')
    expect(tf.status.value).toEqual(ENABLED.data)
  })

  it('status 取得失敗は握りつぶさずトーストと文言で見せる', async () => {
    apiMock.mockRejectedValueOnce(apiError(500))
    const tf = useTwoFactorSettings()
    await tf.loadStatus()
    expect(tf.status.value).toBeNull()
    expect(notificationMock.error).toHaveBeenCalled()
    expect(tf.errorMessage.value).toContain('error.generic')
  })

  it('AC-2/3: TOTP セットアップ開始→6桁で /2fa/verify→バックアップコード表示→status 再取得', async () => {
    apiMock
      .mockResolvedValueOnce({ data: { secret: 'S3CRET', qrCodeUrl: 'otpauth://totp/x' } })
      .mockResolvedValueOnce({ data: { backupCodes: ['a', 'b'] } })
      .mockResolvedValueOnce(ENABLED)
    const tf = useTwoFactorSettings()
    await tf.startTotpSetup()
    expect(tf.totpSetup.value?.secret).toBe('S3CRET')
    expect(await tf.confirmTotpSetup('123456')).toBe(true)
    expect(apiMock).toHaveBeenNthCalledWith(2, '/api/v1/auth/2fa/verify', { method: 'POST', body: { totpCode: '123456' } })
    expect(tf.totpSetup.value).toBeNull()
    expect(tf.backupCodes.value).toEqual(['a', 'b'])
    expect(tf.showBackupCodesDialog.value).toBe(true)
    expect(apiMock).toHaveBeenNthCalledWith(3, '/api/v1/auth/2fa/status')
  })

  it('AC-3: 検証失敗時は設定中のまま・エラー文言が出て再試行できる', async () => {
    apiMock
      .mockResolvedValueOnce({ data: { secret: 'S', qrCodeUrl: 'otpauth://x' } })
      .mockRejectedValueOnce(apiError(400, 'AUTH_074'))
    const tf = useTwoFactorSettings()
    await tf.startTotpSetup()
    expect(await tf.confirmTotpSetup('000000')).toBe(false)
    expect(tf.totpSetup.value).not.toBeNull()
    expect(tf.errorMessage.value).toContain('error.invalid_code')
    expect(tf.showBackupCodesDialog.value).toBe(false)
  })

  it('AC-3: キャンセルで未設定表示に戻る', async () => {
    apiMock.mockResolvedValueOnce({ data: { secret: 'S', qrCodeUrl: 'otpauth://x' } })
    const tf = useTwoFactorSettings()
    await tf.startTotpSetup()
    tf.cancelTotpSetup()
    expect(tf.totpSetup.value).toBeNull()
  })

  it('AC-29: メール2FA は setup → enable でバックアップコードを表示する', async () => {
    apiMock
      .mockResolvedValueOnce({ data: { maskedEmail: 'a***@example.com', expiresInSeconds: 600, resendAfterSeconds: 60 } })
      .mockResolvedValueOnce({ data: { backupCodes: ['x'] } })
      .mockResolvedValueOnce({ data: { enabled: true, method: 'EMAIL', backupCodesRemaining: 8 } })
    const tf = useTwoFactorSettings()
    expect(await tf.startEmailSetup()).toBe(true)
    expect(apiMock).toHaveBeenNthCalledWith(1, '/api/v1/auth/2fa/email/setup', { method: 'POST' })
    expect(await tf.confirmEmailSetup('123456')).toBe(true)
    expect(apiMock).toHaveBeenNthCalledWith(2, '/api/v1/auth/2fa/email/enable', { method: 'POST', body: { code: '123456' } })
    expect(tf.showBackupCodesDialog.value).toBe(true)
    expect(tf.status.value?.method).toBe('EMAIL')
  })

  it('AC-29: AUTH_082 ならメール方式は使えない旨を示し、開始ボタンを無効化できる', async () => {
    apiMock.mockRejectedValueOnce(apiError(400, 'AUTH_082'))
    const tf = useTwoFactorSettings()
    expect(await tf.startEmailSetup()).toBe(false)
    expect(tf.emailAvailable.value).toBe(false)
    expect(tf.errorMessage.value).toContain('error.email_unavailable')
  })

  it('AC-34/44: 無効化はパスワード＋要素を /2fa/disable へ送り、成功で status 再取得とトースト', async () => {
    apiMock.mockResolvedValueOnce({ data: { message: 'ok' } }).mockResolvedValueOnce({ data: { enabled: false, backupCodesRemaining: 0 } })
    const tf = useTwoFactorSettings()
    const ok = await tf.disableTwoFactor({ method: 'TOTP', code: '123456', currentPassword: 'pw' })
    expect(ok).toBe(true)
    expect(apiMock).toHaveBeenNthCalledWith(1, '/api/v1/auth/2fa/disable', {
      method: 'POST',
      body: { method: 'TOTP', code: '123456', currentPassword: 'pw' },
    })
    expect(notificationMock.success).toHaveBeenCalled()
    expect(tf.status.value?.enabled).toBe(false)
  })

  it('AC-34: パスワードを持たない利用者は currentPassword を送らない', async () => {
    apiMock.mockResolvedValueOnce({ data: {} }).mockResolvedValueOnce({ data: { enabled: false } })
    const tf = useTwoFactorSettings()
    await tf.disableTwoFactor({ method: 'BACKUP_CODE', code: 'ABCD1234' })
    expect(apiMock.mock.calls[0]![1].body).toEqual({ method: 'BACKUP_CODE', code: 'ABCD1234' })
  })

  it('AC-34: 本人確認メールOTP を /2fa/reauth/email/send で送る', async () => {
    apiMock.mockResolvedValueOnce({ data: { maskedEmail: 'a***@example.com', expiresInSeconds: 300, resendAfterSeconds: 60 } })
    const tf = useTwoFactorSettings()
    expect(await tf.sendReauthEmail()).toBe(true)
    expect(apiMock).toHaveBeenCalledWith('/api/v1/auth/2fa/reauth/email/send', { method: 'POST' })
    expect(tf.reauthEmail.value?.maskedEmail).toBe('a***@example.com')
  })

  it('AC-44: 再生成は本人確認つきで /2fa/backup-codes/regenerate を呼ぶ', async () => {
    apiMock
      .mockResolvedValueOnce({ data: { backupCodes: ['n1', 'n2'] } })
      .mockResolvedValueOnce(ENABLED)
    const tf = useTwoFactorSettings()
    expect(await tf.regenerateBackupCodes({ method: 'EMAIL', code: '654321', currentPassword: 'pw' })).toBe(true)
    expect(apiMock).toHaveBeenNthCalledWith(1, '/api/v1/auth/2fa/backup-codes/regenerate', {
      method: 'POST',
      body: { method: 'EMAIL', code: '654321', currentPassword: 'pw' },
    })
    expect(tf.backupCodes.value).toEqual(['n1', 'n2'])
  })

  it('AC-44: 再生成はパスワードだけでも送れ、未入力の要素は送らない', async () => {
    apiMock.mockResolvedValueOnce({ data: { backupCodes: ['n1'] } }).mockResolvedValueOnce(ENABLED)
    const tf = useTwoFactorSettings()
    expect(await tf.regenerateBackupCodes({ currentPassword: 'pw' })).toBe(true)
    expect(apiMock.mock.calls[0]![1].body).toEqual({ currentPassword: 'pw' })
  })

  it('AC-44: 再生成は要素だけでも送れ、未入力のパスワードは送らない', async () => {
    apiMock.mockResolvedValueOnce({ data: { backupCodes: ['n1'] } }).mockResolvedValueOnce(ENABLED)
    const tf = useTwoFactorSettings()
    expect(await tf.regenerateBackupCodes({ method: 'TOTP', code: '123456' })).toBe(true)
    expect(apiMock.mock.calls[0]![1].body).toEqual({ method: 'TOTP', code: '123456' })
  })

  it('AC-44: 本人確認に失敗したらコードは表示されずエラーが出る', async () => {
    apiMock.mockRejectedValueOnce(apiError(400, 'AUTH_080'))
    const tf = useTwoFactorSettings()
    expect(await tf.regenerateBackupCodes({ method: 'TOTP', code: '000000', currentPassword: 'bad' })).toBe(false)
    expect(tf.showBackupCodesDialog.value).toBe(false)
    expect(tf.errorMessage.value).toContain('error.reauth_failed')
  })

  it('429 は Retry-After の秒数つきで表示する', async () => {
    apiMock.mockRejectedValueOnce(apiError(429, 'AUTH_077', '30'))
    const tf = useTwoFactorSettings()
    await tf.sendReauthEmail()
    expect(tf.errorMessage.value).toContain('rate_limited')
    expect(tf.errorMessage.value).toContain('30')
  })
})
