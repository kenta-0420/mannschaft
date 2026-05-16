export type SealStatus = 'SEALED' | 'UNSEAL_REQUESTED' | 'UNSEALED' | 'RE_SEALED'

/**
 * 滞納エスカレーションのステージ区分（F09.15 S5-B）。
 * D+30/60/90/120/150 で自動進行する。
 */
export type DelinquencyEscalationStage =
  | 'STAGE_1_REMINDER'
  | 'STAGE_2_EMERGENCY_CONTACT'
  | 'STAGE_3_WATCHER_VISIT'
  | 'STAGE_4_DEATH_SUSPECTED'
  | 'STAGE_5_LEGAL_PREP'

/**
 * 滞納エスカレーション レスポンス型（F09.15 S5-B）。
 */
export interface DelinquencyEscalation {
  id: string
  organizationId: number
  residentRegistryId: number
  dwellingUnitId: number
  currentStage: DelinquencyEscalationStage
  /** 滞納開始日（ISO date 形式: YYYY-MM-DD） */
  delinquencyStartedAt: string
  stage1CompletedAt: string | null
  stage2CompletedAt: string | null
  stage3CompletedAt: string | null
  stage4CompletedAt: string | null
  stage5CompletedAt: string | null
  frozenAt: string | null
  frozenReason: string | null
  resolvedAt: string | null
  /** 解決理由コード（PAID / DEATH_CONFIRMED / MANUAL_CLOSE 等） */
  resolvedReason: string | null
  createdAt: string
  updatedAt: string
}

/**
 * エスカレーション凍結リクエスト型（F09.15 S5-B）。
 */
export interface FreezeEscalationRequest {
  /** 凍結理由（最大 500 文字） */
  reason: string
}

/**
 * エスカレーション解決リクエスト型（F09.15 S5-B）。
 */
export interface ResolveEscalationRequest {
  /** 解決理由コード（PAID / DEATH_CONFIRMED / MANUAL_CLOSE 等、最大 50 文字） */
  resolvedReason: string
}
/**
 * 法的手続き申立種別（F09.15 S6-B）。
 * - ABSENTEE_PROPERTY_MANAGER: 不在者財産管理人選任申立（家事事件手続法 145 条）
 * - INHERITANCE_LIQUIDATOR: 相続財産清算人選任申立（民法 952 条）
 */
export type LegalFilingType = 'ABSENTEE_PROPERTY_MANAGER' | 'INHERITANCE_LIQUIDATOR'

/**
 * 法的手続き レスポンス型（F09.15 S6-B）。
 */
export interface LegalFiling {
  /** 法的手続きレコード ID（UUIDv7）。 */
  id: string
  organizationId: number
  dwellingUnitId: number
  residentRegistryId: number
  filingType: LegalFilingType
  /** 申立書テンプレート PDF の S3 キー。 */
  templatePdfS3Key?: string
  /** 区分所有法 8 条 証拠 ZIP の S3 キー（未生成の場合 undefined）。 */
  evidencePackageS3Key?: string
  /** 証拠 ZIP 生成日時。 */
  evidenceBuiltAt?: string
  /** 証拠 ZIP の SHA-256 ハッシュ。 */
  evidenceSha256?: string
  /** 外部（家庭裁判所等）への提出日時。 */
  filedExternallyAt?: string
  /** 外部受理番号。 */
  externalCaseNumber?: string
  note?: string
  createdAt: string
  updatedAt: string
}

/**
 * 法的手続き起票リクエスト型（F09.15 S6-B）。
 */
export interface CreateLegalFilingRequest {
  residentRegistryId: number
  dwellingUnitId: number
  filingType: LegalFilingType
  note?: string
}

/**
 * 証拠 ZIP ダウンロード URL レスポンス型（F09.15 S6-B）。
 */
export interface EvidenceDownloadUrlResponse {
  downloadUrl: string
  ttlSeconds: number
}

export type UnsealRequestStatus = 'PENDING' | 'FIRST_APPROVED' | 'UNSEALED' | 'RE_SEALED' | 'CANCELLED'

export interface UnsealRequestResponse {
  id: string
  organizationId: number
  preRegistrationId: string
  requestedBy: number
  requestReason: string
  status: UnsealRequestStatus
  firstApproverUserId: number | null
  secondApproverUserId: number | null
  autoResealAt: string | null
  reSealedAt: string | null
  cancelledAt: string | null
  createdAt: string
  updatedAt: string
}

export interface UnsealRequestCreateRequest {
  preRegistrationId: string
  reason: string
}

export interface UnsealApprovalRequest {
  comment?: string | null
}
