import type { AbsenceReason } from './rollCall'

/**
 * F03.12 §15 事前遅刻・欠席連絡の型定義。
 *
 * BE DTO 出典:
 *   - {@code LateNoticeRequest}
 *   - {@code AbsenceNoticeRequest}
 *   - {@code AdvanceNoticeResponse}
 */

/**
 * 事前欠席連絡で指定可能な欠席理由。
 *
 * BE {@code AbsenceNoticeRequest#absenceReason} の Pattern 制約により
 * {@code NOT_ARRIVED} は除外される（事前申告では「未到着」という値はあり得ないため）。
 */
export type AdvanceAbsenceReason = Exclude<AbsenceReason, 'NOT_ARRIVED'>

/** 事前通知の種別。 */
export type AdvanceNoticeType = 'LATE' | 'ABSENCE'

/**
 * 事前遅刻連絡リクエスト。
 *
 * 本人または見守り者が代理で「N 分遅刻予定」を申告する。
 */
export interface LateNoticeRequest {
  /** 遅刻を申告するユーザー（代理申告時はケア対象者の userId）。 */
  userId: number
  /** 遅刻予定分数（1〜120）。 */
  expectedArrivalMinutesLate: number
  /** コメント（500 文字以内、任意）。 */
  comment?: string
}

/**
 * 事前欠席連絡リクエスト。
 *
 * 本人または見守り者が代理で「欠席」を申告する。
 */
export interface AbsenceNoticeRequest {
  /** 欠席を申告するユーザー（代理申告時はケア対象者の userId）。 */
  userId: number
  /** 欠席理由（SICK / PERSONAL_REASON / OTHER）。 */
  absenceReason: AdvanceAbsenceReason
  /** コメント（500 文字以内、任意）。 */
  comment?: string
}

/**
 * 事前通知（遅刻・欠席）レスポンス。
 *
 * BE は遅刻と欠席を 1 つの DTO で返却する。
 * - LATE の場合: {@link expectedArrivalMinutesLate} に値、{@link absenceReason} は null
 * - ABSENCE の場合: {@link absenceReason} に値、{@link expectedArrivalMinutesLate} は null
 */
export interface AdvanceNoticeResponse {
  userId: number
  displayName: string
  noticeType: AdvanceNoticeType
  expectedArrivalMinutesLate: number | null
  absenceReason: AdvanceAbsenceReason | null
  comment: string | null
  /** ISO-8601 形式の作成日時（=申告日時）。 */
  createdAt: string
}
