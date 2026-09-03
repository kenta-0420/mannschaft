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
  color: string | null
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
  /**
   * 割当内容をサーバー側で伏せたか（CMP-260826-2127 / AC-4）。
   *
   * 非管理者が COLLECTING / ADJUSTING のシフト表の枠を取得したときだけ true になり、
   * そのとき assignedUserIds は必ず空配列になる。
   * 「本当に誰も割り当たっていない枠」と区別するために使う（人数バッジの出し分け）。
   * 表示の判定に schedule.status を使ってはならない（BE と規則が二重化するため）。
   */
  assignmentMasked: boolean
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
  /**
   * v2.2: 送信先モード（'SPECIFIC' | 'OPEN_CALL'）
   * 部隊Aが実装した SwapRequest 拡張に対応
   */
  recipientMode?: 'SPECIFIC' | 'OPEN_CALL'
  /** v2.2: 交代候補者として指定されたユーザー ID リスト */
  targetUserIds?: number[]
  /** v2.2: 手挙げした日時 */
  claimedAt?: string | null
}

/**
 * 確定シフト枠レスポンス
 *
 * `GET /api/v1/shifts/my/confirmed-slots` のレスポンス型。
 * 部隊A が実装したマイシフトページ刷新 API に対応（F03.5 Phase 3）。
 */
export interface MyConfirmedSlotResponse {
  /** シフト枠 ID */
  slotId: number
  /** シフト日（YYYY-MM-DD） */
  slotDate: string
  /** 開始時刻（HH:mm:ss） */
  startTime: string
  /** 終了時刻（HH:mm:ss） */
  endTime: string
  /** チーム ID */
  teamId: number
  /** チーム名 */
  teamName: string
  /** スケジュール ID */
  scheduleId: number
  /** スケジュール名 */
  scheduleName: string
  /** ポジション名（未設定の場合は null） */
  positionName: string | null
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
  /**
   * v2.2: オープンコールフラグ。true の場合、全メンバーに公開募集する。
   * false または未指定の場合は `targetUserIds` を優先する。
   */
  openCall?: boolean
  /**
   * v2.2: 交代候補者として指定するユーザー ID リスト。
   * `openCall` が false のとき有効（SPECIFIC モード）。
   */
  targetUserIds?: number[]
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

// 自動割当
export type AssignmentStrategyType = 'MANUAL' | 'GREEDY_V1' | 'CSP_V1'
export type ShiftAssignmentStatus = 'PROPOSED' | 'CONFIRMED' | 'REVOKED'
export type ShiftAssignmentRunStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CONFIRMED' | 'REVOKED'

export interface AssignmentParameters {
  preferenceWeight?: number
  fairnessWeight?: number
  consecutivePenaltyWeight?: number
  respectWorkConstraints?: boolean
  overwriteExisting?: boolean
}

export interface AssignmentWarning {
  code: string
  message: string
  slotId?: number
  userId?: number
}

export interface ProposedAssignment {
  id: number
  slotId: number
  userId: number
  status: ShiftAssignmentStatus
  score?: number
  note?: string
}

export interface AssignmentRun {
  id: number
  scheduleId: number
  strategy: AssignmentStrategyType
  status: ShiftAssignmentRunStatus
  triggeredBy: number
  slotsTotal: number
  slotsFilled: number
  warnings?: AssignmentWarning[]
  parameters?: AssignmentParameters
  errorMessage?: string
  visualReviewConfirmedBy?: number
  visualReviewConfirmedAt?: string
  visualReviewNote?: string
  startedAt: string
  completedAt?: string
  assignments?: ProposedAssignment[]
}

// 勤務制約
export interface WorkConstraint {
  id?: number
  teamId: number
  userId?: number
  maxMonthlyHours?: number
  maxMonthlyDays?: number
  maxConsecutiveDays?: number
  maxNightShiftsPerMonth?: number
  minRestHoursBetweenShifts?: number
  note?: string
}

// 変更依頼
export type ChangeRequestType = 'PRE_CONFIRM_EDIT' | 'INDIVIDUAL_SWAP' | 'OPEN_CALL'
export type ChangeRequestStatus = 'OPEN' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN' | 'EXPIRED'

export interface ChangeRequest {
  id: number
  scheduleId: number
  slotId?: number
  requestType: ChangeRequestType
  status: ChangeRequestStatus
  requestedBy: number
  reason?: string
  reviewerId?: number
  reviewComment?: string
  reviewedAt?: string
  expiresAt?: string
  createdAt: string
}

export interface CreateChangeRequestPayload {
  scheduleId: number
  slotId?: number
  requestType: ChangeRequestType
  reason?: string
}

export interface ReviewChangeRequestPayload {
  decision: 'ACCEPTED' | 'REJECTED'
  reviewComment?: string
  version: number
}

// オープンコール
// SwapRequestStatus は上部（行28付近）で定義済み（重複削除）
