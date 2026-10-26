/**
 * F08.7 シフト予算管理 — 型定義（Phase 10-γ）。
 *
 * 設計書 docs/features/F08.7_shift_budget_integration.md (v1.3) §6.2 に準拠。
 * バックエンド DTO の JSON 形状と完全一致させる（snake_case を維持）。
 */

// ===== 共通 =====

/** 平均時給算出モード */
export type ShiftBudgetRateMode = 'MEMBER_AVG' | 'POSITION_AVG' | 'EXPLICIT'

/** 消化サマリのステータス（4 段階） */
export type ConsumptionStatus = 'OK' | 'WARN' | 'EXCEEDED' | 'SEVERE_EXCEEDED'

/** 失敗イベントのステータス */
export type FailedEventStatus = 'PENDING' | 'RETRYING' | 'EXHAUSTED' | 'MANUAL_RESOLVED'

// ===== 逆算 API（§6.2.2 / Phase 9-α） =====

/**
 * POSITION_AVG モードのポジション別必要人数指定。
 */
export interface PositionRequiredCount {
  position_id: number
  required_count: number
}

/**
 * シフト予算逆算リクエスト。
 */
export interface RequiredSlotsRequest {
  team_id?: number | null
  budget_amount: number
  slot_hours: number
  rate_mode: ShiftBudgetRateMode
  avg_hourly_rate?: number | null
  position_required_counts?: PositionRequiredCount[]
}

/**
 * POSITION_AVG モードのポジション別寄与度内訳。
 */
export interface PositionBreakdown {
  position_id: number
  avg_rate: number | null
  member_count: number
  required_count: number
}

/**
 * シフト予算逆算レスポンス。
 */
export interface RequiredSlotsResponse {
  budget_amount: number
  avg_hourly_rate: number
  slot_hours: number
  required_slots: number
  calculation: string
  warnings: string[]
  position_breakdown?: PositionBreakdown[] | null
}

// ===== 予算割当 CRUD（§6.2.1 / Phase 9-β） =====

/**
 * シフト予算割当 作成リクエスト。
 */
export interface AllocationCreateRequest {
  team_id?: number | null
  project_id?: number | null
  fiscal_year_id: number
  budget_category_id: number
  period_start: string
  period_end: string
  allocated_amount: number
  currency?: string | null
  note?: string | null
}

/**
 * シフト予算割当 更新リクエスト。
 */
export interface AllocationUpdateRequest {
  allocated_amount: number
  note?: string | null
  version: number
}

/**
 * シフト予算割当 レスポンス。
 */
export interface AllocationResponse {
  id: number
  organization_id: number
  team_id: number | null
  project_id: number | null
  fiscal_year_id: number
  budget_category_id: number
  period_start: string
  period_end: string
  allocated_amount: number | null
  consumed_amount: number | null
  confirmed_amount: number | null
  currency: string
  note: string | null
  created_by: number | null
  version: number
  created_at: string
  updated_at: string
}

/**
 * シフト予算割当 一覧レスポンス。
 */
export interface AllocationListResponse {
  items: AllocationResponse[]
  page: number
  size: number
  total: number
}

// ===== 消化サマリ（§6.2.3） =====

/**
 * ユーザー別消化額。BUDGET_ADMIN 保有時のみ実データが返る。
 */
export interface UserConsumption {
  user_id: number
  amount: number
  hours: number
}

/**
 * 警告レスポンス。
 */
export interface AlertResponse {
  id: number
  allocation_id: number
  threshold_percent: number
  triggered_at: string
  consumed_amount_at_trigger: number
  workflow_request_id: number | null
  acknowledged_at: string | null
  acknowledged_by: number | null
}

/**
 * 消化サマリレスポンス。
 */
export interface ConsumptionSummaryResponse {
  allocation_id: number
  allocated_amount: number | null
  consumed_amount: number | null
  confirmed_amount: number | null
  planned_amount: number | null
  remaining_amount: number | null
  consumption_rate: number | null
  status: ConsumptionStatus
  flags: string[]
  alerts?: AlertResponse[]
  by_user?: UserConsumption[]
}

// ===== TODO 紐付（§6.2.4 / Phase 9-γ） =====

export interface TodoBudgetLinkCreateRequest {
  project_id?: number | null
  todo_id?: number | null
  allocation_id: number
  link_amount?: number | null
  link_percentage?: number | null
  currency?: string | null
}

export interface TodoBudgetLinkResponse {
  id: number
  project_id: number | null
  todo_id: number | null
  allocation_id: number
  link_amount: number | null
  link_percentage: number | null
  currency: string
  created_by: number | null
  created_at: string
  updated_at: string
}

// ===== 警告承認（§6.2.5 / Phase 9-δ 第2段） =====

export interface AlertAcknowledgeRequest {
  comment?: string | null
}

// ===== 月次締め（§6.1 #11 / Phase 9-δ 第2段 + 10-β 拡張） =====

export interface MonthlyCloseRequest {
  organization_id: number
  /** YYYY-MM 形式 */
  year_month: string
}

export interface MonthlyCloseResponse {
  organization_id?: number | null
  year_month: string
  closed_allocations: number
  already_closed_allocations: number
  closed_consumptions: number
  processed_organization_ids: number[]
  failed_organization_ids: number[]
  already_closed_organization_ids: number[]
}

// ===== 失敗イベント（Phase 10-β） =====

export interface FailedEventResponse {
  id: number
  organization_id: number
  event_type: string
  source_id: number | null
  error_message: string | null
  retry_count: number
  last_retried_at: string | null
  status: FailedEventStatus
  created_at: string
  updated_at: string
}
