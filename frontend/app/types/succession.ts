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
