/**
 * 欠席・遅刻・早退の理由区分。
 *
 * BE `com.mannschaft.app.school.entity.AbsenceReason` の値と完全一致させること（BE が正）。
 * OpenAPI 生成型（types/generated）には enum が出力されないため手動管理する。
 * 食い違うと提出が 400（COMMON_001）になる。
 *
 * ラベルは i18n キー `school.attendance.absenceReason.<値>` で解決する。
 */
export const ABSENCE_REASONS = [
  'SICK',
  'INJURY',
  'FAMILY_REASON',
  'BEREAVEMENT',
  'INFECTIOUS_DISEASE',
  'MENTAL_HEALTH',
  'OFFICIAL_BUSINESS',
  'OTHER',
] as const

export type AbsenceReasonValue = (typeof ABSENCE_REASONS)[number]
