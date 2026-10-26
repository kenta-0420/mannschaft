/**
 * F08.8 Phase 5 申し送りパック型定義。
 *
 * <p>設計書 docs/features/F08.8_repair_longterm_dashboard.md Phase 5 に準拠。</p>
 */

/** 個人情報レベル。ANONYMIZED は GDPR 対応の匿名化 PDF を生成する。 */
export type PiiLevel = 'STANDARD' | 'ANONYMIZED'

/** 申し送りパックの生成状態。 */
export type PackStatus = 'GENERATING' | 'READY' | 'FAILED'

/** 申し送りパック（生成済み PDF 管理）。 */
export interface HandoverPack {
  id: string
  teamId: number
  status: PackStatus
  piiLevel: PiiLevel
  fileSha256: string | null
  fileSizeBytes: number | null
  termId: number
  memo: string | null
  generatedAt: string | null
  expiresAt: string | null
}

/** 申し送りパックのダウンロード URL レスポンス。署名付き URL は一時的に有効。 */
export interface HandoverPackDownloadResponse {
  downloadUrl: string
  expiresAt: string
  watermarkFor: string
}

/** 申し送りパック生成リクエスト。 */
export interface GenerateHandoverPackRequest {
  termId: number
  memo?: string
  piiLevel?: PiiLevel
}

/** 理事任期。 */
export interface MemberTerm {
  id: number
  teamId: number
  userId: number
  userDisplayName: string
  termStart: string // ISO date
  termEnd: string // ISO date
  roleName: string | null
  isActive: boolean
}

/** 任期作成リクエスト。 */
export interface CreateTermRequest {
  userId: number
  termStart: string
  termEnd: string
  roleName?: string
}
