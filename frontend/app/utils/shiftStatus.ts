import type { ShiftScheduleStatus } from '~/types/shift'

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
