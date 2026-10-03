// F14.1 代理入力・非デジタル住民対応 型定義
import type { components } from '~/types/generated'

/** 代理入力が適用できる機能スコープ */
export type ProxyInputFeatureScope =
  | 'SURVEY'
  | 'SCHEDULE_ATTENDANCE'
  | 'SHIFT_REQUEST'
  | 'ANNOUNCEMENT_READ'
  | 'PARKING_APPLICATION'
  | 'CIRCULAR'
  | 'PAYMENT'

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
  | 'PAPER_BY_SUBJECT'
  | 'AUTO_BY_LIFE_EVENT'
  | 'AUTO_BY_TENURE_END'

/** 代理入力同意書 */
export type ProxyInputConsent = Required<Omit<components['schemas']['ProxyInputConsentResponse'],
  'effectiveUntil' | 'approvedAt' | 'revokedAt' | 'approvedByUserId' | 'witnessUserId'
  | 'revokeMethod' | 'revokeReason' | 'revokeWitnessedByUserId'>> & {
  effectiveUntil: string | null
  approvedAt: string | null
  revokedAt: string | null
  approvedByUserId: number | null
  witnessUserId: number | null
  revokeMethod: string | null
  revokeReason: string | null
  revokeWitnessedByUserId: number | null
}

/** 代理入力デスクのピン留め状態 */
export interface ProxyInputDeskState {
  pinnedSubjectUserId: number | null
  pinnedConsentId: number | null
  inputSource: ProxyInputSource
  originalStorageLocation: string
}

/** 代理入力操作履歴レコード */
export type ProxyInputRecord = Required<Omit<components['schemas']['ProxyInputRecordResponse'],
  'consentId' | 'originalStorageLocation' | 'auditLogId'>> & {
  consentId: number | null
  originalStorageLocation: string | null
  auditLogId: number | null
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
  revokeMethod: 'API_BY_SUBJECT' | 'PAPER_BY_SUBJECT'
  revokeReason?: string
  revokeWitnessedByUserId?: number
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
