// F14.1 代理入力・非デジタル住民対応 型定義

export type ProxyInputFeatureScope =
  | 'SURVEY'
  | 'SCHEDULE_ATTENDANCE'
  | 'SHIFT_REQUEST'
  | 'ANNOUNCEMENT_READ'
  | 'PARKING_APPLICATION'
  | 'CIRCULAR'

export type ProxyInputConsentMethod =
  | 'PAPER_SIGNED'
  | 'WITNESSED_ORAL'
  | 'DIGITAL_SIGNATURE'
  | 'GUARDIAN_BY_COURT'

export type ProxyInputSource =
  | 'PAPER_FORM'
  | 'PHONE_INTERVIEW'
  | 'IN_PERSON'

export type ProxyRevokeMethod =
  | 'API_BY_SUBJECT'
  | 'PAPER'
  | 'LIFE_EVENT'
  | 'TENURE_END'

export interface ProxyInputConsent {
  id: number
  subjectUserId: number
  proxyUserId: number
  orgId: number
  consentMethod: ProxyInputConsentMethod
  effectiveFrom: string        // ISO date string
  effectiveUntil: string       // ISO date string
  approvedAt: string | null
  revokedAt: string | null
  scopes: ProxyInputFeatureScope[]
}

export interface ProxyInputDeskState {
  pinnedSubjectUserId: number | null
  pinnedConsentId: number | null
  inputSource: ProxyInputSource
  originalStorageLocation: string
}

export interface ProxyInputRecord {
  id: number
  proxyInputConsentId: number
  subjectUserId: number
  proxyUserId: number
  featureScope: ProxyInputFeatureScope
  targetEntityType: string
  targetEntityId: number
  inputSource: ProxyInputSource
  originalStorageLocation: string | null
  createdAt: string
}

export interface CreateProxyInputConsentRequest {
  subjectUserId: number
  orgId: number
  consentMethod: ProxyInputConsentMethod
  effectiveFrom: string
  effectiveUntil: string
  scopes: ProxyInputFeatureScope[]
  scanS3Key?: string
}

export interface RevokeProxyInputConsentRequest {
  revokeMethod: ProxyRevokeMethod
  revokeReason?: string
}

export interface ScanUploadUrlResponse {
  uploadUrl: string
  s3Key: string
  expiresInSeconds: number
}

export interface ScanDownloadUrlResponse {
  downloadUrl: string
  expiresInSeconds: number
}
