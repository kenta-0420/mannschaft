import dayjs from 'dayjs'
import type { ShiftScheduleResponse, ShiftScheduleStatus } from '~/types/shift'

/**
 * F03.5 シフトスケジュールステータスユーティリティ。
 *
 * ステータスのライフサイクル:
 * DRAFT(1) → COLLECTING(2) → ADJUSTING(3) → PUBLISHED(4) → ARCHIVED(5)
 */

/**
 * ShiftScheduleStatus を i18n キーに変換する。
 * @param status スケジュールステータス
 * @returns i18n キー文字列（例: `'shift.status.draft'`）
 */
export function statusToI18nKey(status: ShiftScheduleStatus): string {
  const map: Record<ShiftScheduleStatus, string> = {
    DRAFT: 'shift.status.draft',
    COLLECTING: 'shift.status.collecting',
    ADJUSTING: 'shift.status.adjusting',
    PUBLISHED: 'shift.status.published',
    ARCHIVED: 'shift.status.archived',
  }
  return map[status]
}

/**
 * ShiftScheduleStatus をステッパー表示用のステップ番号（1始まり）に変換する。
 *
 * ARCHIVED は PUBLISHED と同じステップ 4 として扱う（完了後のアーカイブ状態）。
 * @param status スケジュールステータス
 * @returns ステップ番号（1〜4）
 */
export function statusToStep(status: ShiftScheduleStatus): number {
  const map: Record<ShiftScheduleStatus, number> = {
    DRAFT: 1,
    COLLECTING: 2,
    ADJUSTING: 3,
    PUBLISHED: 4,
    ARCHIVED: 4,
  }
  return map[status]
}

/**
 * シフト希望をいま提出できるシフト表かを判定する（CMP-260908-2118）。
 *
 * BE の 2 つのガードと同じ規則:
 * - `ShiftRequestService#validateCollectingStatus` … status が COLLECTING でなければ不可
 * - `ShiftRequestService#validateRequestDeadline`  … requestDeadline を過ぎていれば不可
 *
 * チームのシフト表一覧（`ShiftScheduleList`）と一括入力ページ（`/my/shift-request`）の
 * 双方がこの関数を使う。条件式を各画面に散らすと片方だけ直されて食い違うため、
 * 判定はここ 1 か所に置く。
 *
 * @param schedule シフト表（一覧レスポンスに status / requestDeadline が含まれる）
 * @returns 希望を提出できるなら true
 */
export function isAcceptingShiftRequests(schedule: ShiftScheduleResponse): boolean {
  if (schedule.status.status !== 'COLLECTING') return false
  const deadline = schedule.period.requestDeadline
  if (!deadline) return true
  return dayjs().isBefore(dayjs(deadline))
}
