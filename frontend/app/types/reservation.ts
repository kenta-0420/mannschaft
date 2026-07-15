// バックエンドのネスト化された予約 Response DTO に対応した手動型。
// 真実のソースは frontend/app/types/generated/index.ts（openapi-typescript 自動生成）。
// 当ファイルは生成型のスキーマと厳密一致させること（フィールドは BE 同様すべて optional）。
import type { components } from '~/types/generated'

/** F03.4.3 §5.6#10: 一覧応答へ additive 追加されたグループ要約（単枠予約は null）。生成型を単一ソースにする。 */
export type GroupSummaryDto = components['schemas']['GroupSummaryDto']

export type ReservationStatus =
  | 'PENDING'
  | 'CONFIRMED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'NO_SHOW'

export type SlotStatus = 'AVAILABLE' | 'FULL' | 'CLOSED'

// === ReservationResponse（予約そのもの。ネスト構造）===
export interface ReservationIdentifierDto {
  reservationSlotId?: number
  lineId?: number
  teamId?: number
  userId?: number
  /** 予約者の実名（BE が会員名エンリッチで返す）。 */
  userName?: string
}

/** 予約の枠サマリ。my予約画面の日時・線名表示はここを使う（既存バグ根治で追加）。 */
export interface SlotSummaryDto {
  lineName?: string
  title?: string
  /** Format: date (YYYY-MM-DD) */
  slotDate?: string
  /** Format: time (HH:mm:ss) */
  startTime?: string
  /** Format: time (HH:mm:ss) */
  endTime?: string
}

export interface ReservationStatusDto {
  status?: string
  bookedAt?: string
  confirmedAt?: string
  completedAt?: string
}

export interface CancellationDto {
  cancelledAt?: string
  cancelReason?: string
  cancelledBy?: string
}

export interface NotesDto {
  userNote?: string
  adminNote?: string
}

export interface ReservationAuditDto {
  createdAt?: string
  updatedAt?: string
}

export interface ReservationResponse {
  id?: number
  identifier?: ReservationIdentifierDto
  slot?: SlotSummaryDto
  status?: ReservationStatusDto
  cancellation?: CancellationDto
  notes?: NotesDto
  audit?: ReservationAuditDto
  /** グループ予約（F03.4.3）の要約。単枠予約は null（additive・既存契約不変）。 */
  group?: GroupSummaryDto
}

// === ReservationSlotResponse（予約枠。ReservationResponse とは別物）===
export interface SlotBasicDto {
  title?: string
  /** Format: date (YYYY-MM-DD) */
  slotDate?: string
  /** Format: time (HH:mm:ss) */
  startTime?: string
  /** Format: time (HH:mm:ss) */
  endTime?: string
}

export interface SlotStatusDto {
  slotStatus?: string
  bookedCount?: number
  /** 予約枠の定員（同時にこの枠を予約できる人数の上限。既定 1） */
  capacity?: number
  isException?: boolean
  closedReason?: string
  note?: string
}

export interface SlotRecurrenceDto {
  recurrenceRule?: string
  parentSlotId?: number
}

export interface SlotPricingDto {
  price?: number
}

export interface SlotAuditDto {
  createdBy?: number
  createdAt?: string
  updatedAt?: string
}

/** 枠単位の承認ポリシー（null=チーム設定に従う） */
export interface SlotPolicyDto {
  /** AUTO=自動確定 / MANUAL=承認制 / undefined=チーム設定に従う */
  approvalMode?: 'AUTO' | 'MANUAL'
}

export interface ReservationSlotResponse {
  id?: number
  teamId?: number
  staffUserId?: number
  basic?: SlotBasicDto
  status?: SlotStatusDto
  recurrence?: SlotRecurrenceDto
  pricing?: SlotPricingDto
  policy?: SlotPolicyDto
  audit?: SlotAuditDto
}

// === ReservationLineResponse（予約ライン）===
export interface LineMetaDto {
  name?: string
  description?: string
  displayOrder?: number
  isActive?: boolean
  defaultStaffUserId?: number
}

export interface ReservationLineAuditDto {
  createdAt?: string
  updatedAt?: string
}

export interface ReservationLineResponse {
  id?: number
  teamId?: number
  meta?: LineMetaDto
  audit?: ReservationLineAuditDto
}

// === BlockedTimeResponse（予約ブロック時間）===
export interface TimeSlotDto {
  /** Format: date (YYYY-MM-DD) */
  blockedDate?: string
  /** Format: time (HH:mm:ss) */
  startTime?: string
  /** Format: time (HH:mm:ss) */
  endTime?: string
}

export interface BlockedAuditDto {
  reason?: string
  createdBy?: number
  createdAt?: string
  updatedAt?: string
}

export interface BlockedTimeResponse {
  id?: number
  teamId?: number
  timeSlot?: TimeSlotDto
  audit?: BlockedAuditDto
}

// === BusinessHourResponse（営業時間）===
export interface BusinessStatusDto {
  dayOfWeek?: string
  isOpen?: boolean
  /** Format: time (HH:mm:ss) */
  openTime?: string
  /** Format: time (HH:mm:ss) */
  closeTime?: string
}

export interface BusinessHourResponse {
  id?: number
  teamId?: number
  businessStatus?: BusinessStatusDto
}

/**
 * 予約対象の呼称プリセット（F03.4.5 §5.1・BE enum `ReservationResourceNameType`）。
 * DEFAULT=未設定チームの従来表示「予約対象」への後方互換フォールバック。CUSTOM は
 * `resourceNameCustom` の自由入力文字列を全ロケール共通でそのまま表示する（翻訳しない）。
 */
export type ReservationResourceNameTypeCode = 'DEFAULT' | 'STAFF' | 'SEAT' | 'COURT' | 'BED' | 'LANE' | 'CUSTOM'

// === ReservationSettingsResponse（予約設定）===
// 真実のソースは generated/index.ts の ReservationSettingsResponse。
// こちらは composable の import 先として残し、生成型と整合を保つ。
export interface ReservationSettingsResponse {
  /** 非所属ユーザーの予約を許可するか（既定 false）。BE: reservation_team_settings.allow_public_reservation */
  allowPublicReservation?: boolean
  maxAdvanceBookingDays?: number
  minAdvanceBookingHours?: number
  requireConfirmation?: boolean
  allowCancellation?: boolean
  cancellationDeadlineHours?: number
  /** 承認モード。AUTO=自動確定 / MANUAL=承認制（#1640 追加）*/
  approvalMode?: 'AUTO' | 'MANUAL'
  /** キャンセル受付の締切（予約開始の何時間前まで。0〜8760）*/
  cancelDeadlineHours?: number
  /** リマインド送信タイミングの CSV 文字列（例: "24,1"）*/
  remindBeforeHours?: string
  /** 営業時間が設定済みか（F03.4.5 §3.2・実測フィールド）。false=週間スケジュール画面に初回体験ガイドを表示 */
  hasBusinessHours?: boolean
  /** 予約対象の呼称プリセット（F03.4.5 §5.1）。DEFAULT=未設定（従来の『予約対象』表示） */
  resourceNameType?: ReservationResourceNameTypeCode
  /** 自由入力の呼称（resourceNameType=CUSTOM のときのみ非 null） */
  resourceNameCustom?: string
}

// === リクエスト DTO ===
export interface UpdateReservationSettingRequest {
  allowPublicReservation?: boolean
  /** 承認モード変更時に指定（AUTO/MANUAL）*/
  approvalMode?: 'AUTO' | 'MANUAL'
  /** キャンセル受付の締切（時間）変更時に指定 */
  cancelDeadlineHours?: number
  /** リマインドタイミングの CSV 文字列変更時に指定（例: "24,1"）*/
  remindBeforeHours?: string
  /** 予約対象の呼称プリセット変更時に指定（null=据え置き） */
  resourceNameType?: ReservationResourceNameTypeCode
  /** 自由入力の呼称変更時に指定（CUSTOM 選択時のみ有効・30文字以内・null=据え置き） */
  resourceNameCustom?: string
}

export interface CreateReservationRequest {
  slotId: number
  serviceNotes?: string
}

export interface CreateReservationLineRequest {
  name?: string
  description?: string
  displayOrder?: number
  defaultStaffUserId?: number
}

export interface CreateSlotRequest {
  staffUserId?: number
  title?: string
  /** Format: date (YYYY-MM-DD) */
  slotDate: string
  /** Format: time (HH:mm:ss) */
  startTime: string
  /** Format: time (HH:mm:ss) */
  endTime: string
  recurrenceRule?: string
  price?: number
  note?: string
  /** 枠単位の承認モード上書き（省略=チーム設定に従う） */
  approvalMode?: 'AUTO' | 'MANUAL'
  /** 予約枠の定員（同時にこの枠を予約できる人数の上限。省略=既定 1・1 以上） */
  capacity?: number
}

export interface UpdateSlotRequest {
  staffUserId?: number
  title?: string
  /** Format: date (YYYY-MM-DD) */
  slotDate?: string
  /** Format: time (HH:mm:ss) */
  startTime?: string
  /** Format: time (HH:mm:ss) */
  endTime?: string
  price?: number
  note?: string
  /** 枠単位の承認モード上書き（clearApprovalMode=true と同時指定不可） */
  approvalMode?: 'AUTO' | 'MANUAL'
  /** true にすると承認モード上書きを解除（チーム設定に従う状態に戻す） */
  clearApprovalMode?: boolean
  /** 予約枠の定員（省略=据え置き・1 以上） */
  capacity?: number
}
