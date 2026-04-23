/**
 * F03.5 シフト管理 — フロントエンド型定義
 *
 * v2 対応: 5段階 ShiftPreference / 任意勤務制約 / v1 互換 unavailableCount
 */

// =====================================================
// Enum 型
// =====================================================

/** シフト希望強度 5段階（v2 新規。v1 互換: UNAVAILABLE は STRONG_REST に変換済み） */
export type ShiftPreference =
  | 'PREFERRED'
  | 'AVAILABLE'
  | 'WEAK_REST'
  | 'STRONG_REST'
  | 'ABSOLUTE_REST'

/** シフトスケジュールのライフサイクルステータス */
export type ShiftScheduleStatus =
  | 'DRAFT'
  | 'COLLECTING'
  | 'ADJUSTING'
  | 'PUBLISHED'
  | 'ARCHIVED'

/** シフト交代リクエストのステータス（v2.1 拡張含む） */
export type SwapRequestStatus =
  | 'PENDING'
  | 'OPEN_CALL'
  | 'CLAIMED'
  | 'ACCEPTED'
  | 'APPROVED'
  | 'REJECTED'
  | 'CANCELLED'

/** ポジション期間タイプ */
export type ShiftPeriodType = 'WEEKLY' | 'MONTHLY' | 'CUSTOM'

// =====================================================
// レスポンス DTO 型
// =====================================================

/** シフトスケジュールレスポンス */
export interface ShiftScheduleResponse {
  id: number
  teamId: number
  title: string
  periodType: ShiftPeriodType | null
  startDate: string
  endDate: string
  status: ShiftScheduleStatus
  requestDeadline: string | null
  note: string | null
  createdBy: number | null
  publishedAt: string | null
  publishedBy: number | null
  createdAt: string
  updatedAt: string
}

/** シフトポジションレスポンス */
export interface ShiftPositionResponse {
  id: number
  teamId: number
  name: string
  displayOrder: number
  isActive: boolean
  createdAt: string
}

/** シフト枠レスポンス */
export interface ShiftSlotResponse {
  id: number
  scheduleId: number
  slotDate: string
  startTime: string
  endTime: string
  positionId: number | null
  positionName: string | null
  requiredCount: number
  assignedUserIds: number[]
  note: string | null
}

/** シフト希望レスポンス */
export interface ShiftRequestResponse {
  id: number
  scheduleId: number
  userId: number
  slotId: number | null
  slotDate: string
  preference: ShiftPreference
  note: string | null
  submittedAt: string
}

/**
 * シフト希望提出サマリーレスポンス（v2 対応）
 *
 * v1 互換フィールド: `unavailableCount` = `strongRestCount + absoluteRestCount`
 */
export interface ShiftRequestSummaryResponse {
  scheduleId: number
  totalMembers: number
  submittedCount: number
  pendingCount: number
  /** v2 新規: PREFERRED 希望の件数 */
  preferredCount: number
  /** v2 新規: AVAILABLE 希望の件数 */
  availableCount: number
  /** v2 新規: WEAK_REST 希望の件数 */
  weakRestCount: number
  /** v2 新規: STRONG_REST 希望の件数 */
  strongRestCount: number
  /** v2 新規: ABSOLUTE_REST 希望の件数 */
  absoluteRestCount: number
  /** v1 互換: strongRestCount + absoluteRestCount の合計 */
  unavailableCount: number
}

/** シフト交代リクエストレスポンス */
export interface SwapRequestResponse {
  id: number
  slotId: number
  requesterId: number
  accepterId: number | null
  status: SwapRequestStatus
  reason: string | null
  adminNote: string | null
  resolvedBy: number | null
  resolvedAt: string | null
  createdAt: string
  /** v2.1: オープンコールフラグ */
  isOpenCall?: boolean
  /** v2.1: 手挙げしたユーザー ID */
  claimedBy?: number | null
}

/** デフォルト勤務可能時間レスポンス */
export interface AvailabilityDefaultResponse {
  id: number
  userId: number
  teamId: number
  dayOfWeek: number
  startTime: string | null
  endTime: string | null
  preference: ShiftPreference
  note: string | null
}

/** 時給設定レスポンス */
export interface ShiftHourlyRateResponse {
  id: number
  userId: number
  teamId: number
  hourlyRate: string
  effectiveFrom: string
  createdAt: string
}

/**
 * メンバー勤務制約レスポンス（v2 新規）
 *
 * `userId` が null の場合はチームデフォルト（全メンバー適用）を示す。
 */
export interface MemberWorkConstraintResponse {
  id: number
  teamId: number
  /** null = チームデフォルト */
  userId: number | null
  maxMonthlyHours: string | null
  maxMonthlyDays: number | null
  maxConsecutiveDays: number | null
  maxNightShiftsPerMonth: number | null
  minRestHoursBetweenShifts: string | null
  note: string | null
}

// =====================================================
// リクエスト DTO 型
// =====================================================

/** シフトスケジュール作成リクエスト */
export interface CreateShiftScheduleRequest {
  title: string
  periodType?: ShiftPeriodType
  startDate: string
  endDate: string
  requestDeadline?: string
  note?: string
  copyFromScheduleId?: number
}

/** シフトスケジュール更新リクエスト */
export interface UpdateShiftScheduleRequest {
  title?: string
  periodType?: ShiftPeriodType
  startDate?: string
  endDate?: string
  status?: ShiftScheduleStatus
  requestDeadline?: string
  note?: string
}

/** シフト枠作成リクエスト */
export interface CreateShiftSlotRequest {
  slotDate: string
  startTime: string
  endTime: string
  positionId?: number
  requiredCount?: number
  note?: string
}

/** シフト枠一括作成リクエスト */
export interface BulkCreateShiftSlotRequest {
  slots: CreateShiftSlotRequest[]
}

/** シフト枠更新リクエスト */
export interface UpdateShiftSlotRequest {
  slotDate?: string
  startTime?: string
  endTime?: string
  positionId?: number
  requiredCount?: number
  assignedUserIds?: number[]
  note?: string
}

/** シフト希望提出リクエスト */
export interface CreateShiftRequestRequest {
  scheduleId: number
  slotId?: number
  slotDate: string
  preference: ShiftPreference
  note?: string
}

/** シフト希望更新リクエスト */
export interface UpdateShiftRequestRequest {
  preference: ShiftPreference
  note?: string
}

/** シフト交代リクエスト作成 */
export interface CreateSwapRequestRequest {
  slotId: number
  reason?: string
}

/** シフト交代リクエスト承認・却下 */
export interface ResolveSwapRequestRequest {
  action: 'approve' | 'reject'
  adminNote?: string
}

/** ポジション作成リクエスト */
export interface CreatePositionRequest {
  name: string
  displayOrder?: number
}

/** ポジション更新リクエスト */
export interface UpdatePositionRequest {
  name?: string
  displayOrder?: number
  isActive?: boolean
}

/** デフォルト勤務可能時間設定リクエスト */
export interface AvailabilityDefaultRequest {
  dayOfWeek: number
  startTime: string
  endTime: string
  preference: ShiftPreference
  note?: string
}

/** デフォルト勤務可能時間一括設定リクエスト */
export interface BulkAvailabilityDefaultRequest {
  availabilities: AvailabilityDefaultRequest[]
}

/** 時給設定作成リクエスト */
export interface CreateHourlyRateRequest {
  userId: number
  hourlyRate: number
  effectiveFrom: string
}

/**
 * メンバー勤務制約作成・更新リクエスト（v2 新規）
 *
 * 全項目 NULL 可能（オプトイン方式）。ただし全項目 null は 400 エラー。
 */
export interface MemberWorkConstraintRequest {
  maxMonthlyHours?: number
  maxMonthlyDays?: number
  maxConsecutiveDays?: number
  maxNightShiftsPerMonth?: number
  minRestHoursBetweenShifts?: number
  note?: string
}
