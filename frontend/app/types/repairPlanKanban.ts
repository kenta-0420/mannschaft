export type KanbanStage =
  | 'REQUESTED'
  | 'RECEIVED'
  | 'UNDER_REVIEW'
  | 'SHORTLISTED'
  | 'SELECTED'
  | 'REJECTED'

export type VisibilityLevel = 'HIDDEN' | 'ANONYMIZED' | 'FULL'
export type KanbanStatus = 'OPEN' | 'CLOSED' | 'AWARDED' | 'CANCELED'
export type ComplianceCheckStatus = 'UNCHECKED' | 'PASSED' | 'FAILED' | 'EXPIRED'

export interface QuoteCard {
  id: string
  kanbanId: string
  vendorId: number
  vendorNameSnapshot: string | null // visibility フィルタ後（null = HIDDEN）
  stage: KanbanStage
  amount: number | null // visibility フィルタ後（null = HIDDEN）
  amountLabel: string | null // ANONYMIZED の場合はレンジ文字列
  complianceCheckStatus: ComplianceCheckStatus
  displayOrder: number
  createdAt: string
}

export interface QuoteKanban {
  id: string
  title: string
  scopeType: string
  scopeId: string
  organizationId: number
  workPackageId: number | null
  repairPlanItemId: string | null
  bidDeadlineAt: string
  visibilityToMember: VisibilityLevel
  status: KanbanStatus
  cards: QuoteCard[]
  createdAt: string
  updatedAt: string
}

export interface CreateKanbanRequest {
  title: string
  workPackageId?: number
  repairPlanItemId?: string
  bidDeadlineAt: string
  visibilityToMember: VisibilityLevel
}

export interface AddCardRequest {
  vendorId: number
  vendorNameSnapshot: string
  amount?: number
  breakdownJson?: string
}

export interface MoveCardRequest {
  newStage: KanbanStage
}

export interface UpdateKanbanRequest {
  title?: string
  bidDeadlineAt?: string
  visibilityToMember?: VisibilityLevel
  status?: KanbanStatus
}

// stage の前進マッピング（UI での遷移ボタン表示に使用）
export const STAGE_ORDER: KanbanStage[] = [
  'REQUESTED',
  'RECEIVED',
  'UNDER_REVIEW',
  'SHORTLISTED',
  'SELECTED',
]

export const TERMINAL_STAGES: KanbanStage[] = ['SELECTED', 'REJECTED']

export const ALL_STAGES: KanbanStage[] = [...STAGE_ORDER, 'REJECTED']
// F08.8 Phase 4
