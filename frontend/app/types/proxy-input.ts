// F14.1 代理入力・非デジタル住民対応 型定義

/** 代理入力が適用できる機能スコープ */
export type ProxyInputFeatureScope =
  | 'SURVEY'
  | 'SCHEDULE_ATTENDANCE'
  | 'SHIFT_REQUEST'
  | 'ANNOUNCEMENT_READ'
  | 'PARKING_APPLICATION'
  | 'CIRCULAR'

/** 同意書の取得方法 */
export type ProxyInputConsentMethod =
  | 'PAPER_SIGNED'
  | 'WITNESSED_ORAL'
  | 'DIGITAL_SIGNATURE'
  | 'GUARDIAN_BY_COURT'

/** 代理入力の入力手段 */
export type ProxyInputSource =
  | 'PAPER_FORM'
  | 'PHONE_INTERVIEW'
  | 'IN_PERSON'

/** 同意撤回の方法 */
export type ProxyRevokeMethod =
  | 'API_BY_SUBJECT'
  | 'PAPER'
  | 'LIFE_EVENT'
  | 'TENURE_END'

/** 代理入力同意書 */
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

/** 代理入力デスクのピン留め状態 */
export interface ProxyInputDeskState {
  pinnedSubjectUserId: number | null
  pinnedConsentId: number | null
  inputSource: ProxyInputSource
  originalStorageLocation: string
}

/** 代理入力操作履歴レコード */
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

/** 同意書登録リクエスト */
export interface CreateProxyInputConsentRequest {
  subjectUserId: number
  orgId: number
  consentMethod: ProxyInputConsentMethod
  effectiveFrom: string
  effectiveUntil: string
  scopes: ProxyInputFeatureScope[]
  scanS3Key?: string
}

/** 同意書撤回リクエスト */
export interface RevokeProxyInputConsentRequest {
  revokeMethod: ProxyRevokeMethod
  revokeReason?: string
}

/** スキャン画像アップロード用 presigned URL レスポンス */
export interface ScanUploadUrlResponse {
  uploadUrl: string
  s3Key: string
  expiresInSeconds: number
}

/** スキャン画像ダウンロード用 presigned URL レスポンス */
export interface ScanDownloadUrlResponse {
  downloadUrl: string
  expiresInSeconds: number
}
