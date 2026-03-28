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

  // === OAuth ===
  async function confirmOAuthLink(token: string) {
    return api(`/api/v1/auth/oauth/link/confirm?token=${encodeURIComponent(token)}`, {
      method: 'POST',
    })
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
    confirmOAuthLink,
    resendVerificationEmail,
  }
}
