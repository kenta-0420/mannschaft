import type {
  TotpSetupResponse,
  BackupCodesResponse,
  TokenResponse,
  AuthSessionResponse,
  WebAuthnCredentialResponse,
  WebAuthnRegisterBeginResponse,
  WebAuthnRegisterCompleteRequest,
  WebAuthnLoginBeginResponse,
  WebAuthnLoginCompleteRequest,
  WebAuthnReauthenticateBeginResponse,
  WebAuthnReauthenticateCompleteRequest,
  WebAuthnReauthenticateCompleteResponse,
  UpdateWebAuthnCredentialRequest,
  MessageResponse,
} from '~/types/auth'

export function useAuthApi() {
  const api = useApi()

  // === 2FA ===
  async function setup2fa() {
    return api<{ data: TotpSetupResponse }>('/api/v1/auth/2fa/setup', {
      method: 'POST',
    })
  }

  async function regenerateBackupCodes() {
    return api<{ data: BackupCodesResponse }>('/api/v1/auth/2fa/backup-codes/regenerate', {
      method: 'POST',
    })
  }

  async function requestMfaRecovery(mfaSessionToken: string) {
    return api<{ data: MessageResponse }>(
      `/api/v1/auth/2fa/recovery/request?mfaSessionToken=${encodeURIComponent(mfaSessionToken)}`,
      {
        method: 'POST',
      },
    )
  }

  async function confirmMfaRecovery(token: string) {
    return api<{ data: TokenResponse }>(
      `/api/v1/auth/2fa/recovery/confirm?token=${encodeURIComponent(token)}`,
      {
        method: 'POST',
      },
    )
  }

  // === Sessions ===
  async function getSessions() {
    return api<{ data: AuthSessionResponse[] }>('/api/v1/auth/sessions')
  }

  async function revokeSession(id: number) {
    return api(`/api/v1/auth/sessions/${id}`, { method: 'DELETE' })
  }

  async function revokeAllSessions() {
    return api('/api/v1/auth/sessions', { method: 'DELETE' })
  }

  // === WebAuthn ===
  async function getWebAuthnCredentials() {
    return api<{ data: WebAuthnCredentialResponse[] }>('/api/v1/auth/webauthn/credentials')
  }

  async function deleteWebAuthnCredential(id: number) {
    return api(`/api/v1/auth/webauthn/credentials/${id}`, { method: 'DELETE' })
  }

  async function updateWebAuthnCredential(id: number, body: UpdateWebAuthnCredentialRequest) {
    return api<{ data: WebAuthnCredentialResponse }>(`/api/v1/auth/webauthn/credentials/${id}`, {
      method: 'PATCH',
      body,
    })
  }

  async function beginWebAuthnRegister() {
    return api<{ data: WebAuthnRegisterBeginResponse }>('/api/v1/auth/webauthn/register/begin', {
      method: 'POST',
    })
  }

  async function completeWebAuthnRegister(body: WebAuthnRegisterCompleteRequest) {
    return api<{ data: MessageResponse }>('/api/v1/auth/webauthn/register/complete', {
      method: 'POST',
      body,
    })
  }

  async function beginWebAuthnLogin() {
    return api<{ data: WebAuthnLoginBeginResponse }>('/api/v1/auth/webauthn/login/begin', {
      method: 'POST',
    })
  }

  async function completeWebAuthnLogin(body: WebAuthnLoginCompleteRequest) {
    return api<{ data: TokenResponse }>('/api/v1/auth/webauthn/login/complete', {
      method: 'POST',
      body,
    })
  }

  /**
   * F18 提示モード追加保護: WebAuthn 再認証開始（設計書 §9.6 / POINT_CARD_009）。
   *
   * <p>認証済みユーザーが提示モード起動前に行う本人確認。サーバー側で 5 分 TTL の
   * チャレンジを記録し、完了 API で「再認証済みフラグ」を立てる。
   * 既存の {@code beginWebAuthnLogin} と異なり email パラメータを取らない。
   */
  async function beginWebAuthnReauthenticate() {
    return api<{ data: WebAuthnReauthenticateBeginResponse }>(
      '/api/v1/auth/webauthn/reauthenticate-begin',
      { method: 'POST' },
    )
  }

  /**
   * F18 提示モード追加保護: WebAuthn 再認証完了（設計書 §9.6 / POINT_CARD_009）。
   *
   * <p>サーバー側で署名検証を行い、成功時に 5 分間有効な「再認証済みフラグ」を立てる。
   * <strong>AT/RT は再発行されない</strong>（{@code completeWebAuthnLogin} とは別フロー）。
   */
  async function completeWebAuthnReauthenticate(body: WebAuthnReauthenticateCompleteRequest) {
    return api<{ data: WebAuthnReauthenticateCompleteResponse }>(
      '/api/v1/auth/webauthn/reauthenticate-complete',
      { method: 'POST', body },
    )
  }

  // === 2FA Setup Verify ===
  async function verifyTotpSetup(code: string) {
    return api<{ data: MessageResponse }>('/api/v1/auth/2fa/verify', {
      method: 'POST',
      body: { totpCode: code },
    })
  }

  // === OAuth ===
  async function loginWithOAuth(provider: string, body: Record<string, unknown>) {
    return api<{ data: TokenResponse }>(`/api/v1/auth/oauth/${provider}`, {
      method: 'POST',
      body,
    })
  }

  async function confirmOAuthLink(token: string) {
    return api(`/api/v1/auth/oauth/link/confirm?token=${encodeURIComponent(token)}`, {
      method: 'POST',
    })
  }

  // === Invite QR ===
  async function getInviteQrCode(token: string) {
    return api<Blob>(`/api/v1/invite/${token}/qr`)
  }

  // === Email Verification ===
  async function resendVerificationEmail(email: string) {
    return api<{ data: MessageResponse }>(
      `/api/v1/auth/verify-email/resend?email=${encodeURIComponent(email)}`,
      {
        method: 'POST',
      },
    )
  }

  return {
    setup2fa,
    regenerateBackupCodes,
    requestMfaRecovery,
    confirmMfaRecovery,
    getSessions,
    revokeSession,
    revokeAllSessions,
    getWebAuthnCredentials,
    deleteWebAuthnCredential,
    updateWebAuthnCredential,
    beginWebAuthnRegister,
    completeWebAuthnRegister,
    beginWebAuthnLogin,
    completeWebAuthnLogin,
    beginWebAuthnReauthenticate,
    completeWebAuthnReauthenticate,
    verifyTotpSetup,
    loginWithOAuth,
    confirmOAuthLink,
    getInviteQrCode,
    resendVerificationEmail,
  }
}
