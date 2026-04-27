/**
 * F03.12 §14 主催者点呼（一括チェックイン）の型定義。
 *
 * BE DTO 出典:
 *   - {@code RollCallCandidateResponse}
 *   - {@code RollCallEntryRequest}
 *   - {@code RollCallSessionRequest}
 *   - {@code RollCallSessionResponse}
 */

/**
 * 欠席理由コード。BE {@code RollCallEntryRequest#absenceReason} の文字列値。
 *
 * - {@code SICK} 病気
 * - {@code PERSONAL_REASON} 個人的事情
 * - {@code OTHER} その他
 * - {@code NOT_ARRIVED} 連絡なし未到着
 */
export type AbsenceReason = 'SICK' | 'PERSONAL_REASON' | 'OTHER' | 'NOT_ARRIVED'

/** 点呼結果ステータス。 */
export type RollCallStatus = 'PRESENT' | 'LATE' | 'ABSENT'

/** RSVP 回答ステータス。 */
export type RsvpStatus = 'ATTENDING' | 'MAYBE' | 'NOT_ATTENDING' | 'NO_RESPONSE'

/**
 * 点呼候補者 1 名のスナップショット。
 *
 * BE は RSVP=ATTENDING/MAYBE のメンバーをこの形で返す。
 */
export interface RollCallCandidate {
  /** ユーザーID。 */
  userId: number
  /** 表示名。 */
  displayName: string
  /** アバター URL（任意）。 */
  avatarUrl: string | null
  /** RSVP 回答状態（NO_RESPONSE もありうる）。 */
  rsvpStatus: RsvpStatus | null
  /** 既にチェックイン済みかどうか。 */
  isAlreadyCheckedIn: boolean
  /** ケア対象者フラグ。true の場合 PRESENT 記録時に保護者通知。 */
  isUnderCare: boolean
  /** 登録済み見守り者数。isUnderCare=true && watcherCount=0 で警告対象。 */
  watcherCount: number
}

/**
 * 点呼セッション内の 1 名分の出欠記録（リクエスト用）。
 *
 * - status=LATE の場合は {@link lateArrivalMinutes} を付与すること（1〜）。
 * - status=ABSENT の場合は {@link absenceReason} を付与すること。
 */
export interface RollCallEntry {
  userId: number
  status: RollCallStatus
  lateArrivalMinutes?: number
  absenceReason?: AbsenceReason
}

/**
 * 点呼セッション一括登録リクエスト。
 *
 * {@link rollCallSessionId} は冪等キー（クライアント側で UUID 生成）。
 * 同一 ID + userId の組み合わせが既存の場合は UPDATE として扱われる。
 */
export interface RollCallSessionRequest {
  rollCallSessionId: string
  entries: RollCallEntry[]
  notifyGuardiansImmediately: boolean
}

/**
 * 点呼セッション一括登録レスポンス。
 *
 * {@link guardianSetupWarnings} は「ケア対象だが見守り者未設定」のユーザー名一覧。
 */
export interface RollCallSessionResponse {
  rollCallSessionId: string
  createdCount: number
  updatedCount: number
  guardianNotificationsSent: number
  guardianSetupWarnings: string[]
}

/**
 * 点呼結果個別修正リクエスト（BE は同じ {@code RollCallEntryRequest} を受け取る）。
 *
 * <p>PATCH /roll-call/{userId} で送信する body。userId はパスから渡すため
 * body 側からは省略する形でも BE は受け付ける（@NotNull 制約のため別途渡す運用）。</p>
 */
export interface RollCallEntryPatchRequest {
  userId: number
  status: RollCallStatus
  lateArrivalMinutes?: number
  absenceReason?: AbsenceReason
}
