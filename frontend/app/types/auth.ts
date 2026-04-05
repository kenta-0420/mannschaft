// === 2FA ===
export interface TotpSetupResponse {
  secret: string
  qrCodeUrl: string
}

export interface BackupCodesResponse {
  backupCodes: string[]
}

export interface TokenResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
}

// === Sessions ===
export interface AuthSessionResponse {
  id: number
  ipAddress: string
  userAgent: string
  lastActivityAt: string
  createdAt: string
  isCurrent: boolean
}

// === WebAuthn ===
export interface WebAuthnCredentialResponse {
  id: number
  credentialId: string
  deviceName: string
  aaguid: string
  lastUsedAt: string | null
  createdAt: string
}

export interface WebAuthnRegisterBeginResponse {
  challenge: string
  rpId: string
  rpName: string
  userId: number
  userDisplayName: string
}

export interface WebAuthnRegisterCompleteRequest {
  credentialId: string
  attestationObject: string
  clientDataJson: string
  publicKey: string
  deviceName?: string
  aaguid?: string
}

export interface WebAuthnLoginBeginResponse {
  challenge: string
  rpId: string
  allowCredentials: string[]
  timeout: number
}

export interface WebAuthnLoginCompleteRequest {
  credentialId: string
  authenticatorData: string
  clientDataJson: string
  signature: string
  signCount: number
}

export interface UpdateWebAuthnCredentialRequest {
  deviceName: string
}

// === OAuth ===
export interface OAuthLinkConfirmRequest {
  token: string
}

// === MessageResponse ===
export interface MessageResponse {
  message: string
}
