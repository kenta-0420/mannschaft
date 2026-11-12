// 学期 API のエラー応答（{ error: { code, message, fieldErrors } }）を画面向けに解釈する。
import { parseApiError } from '~/utils/apiError'
import type { ApiErrorInfo } from '~/utils/apiError'

export const TERM_HAS_TIMETABLES_CODE = 'TIMETABLE_022'
/** 学期の日付範囲が同一スコープ・同一年度の他の学期と重なる（409）。 */
export const TERM_DATE_OVERLAP_CODE = 'TIMETABLE_021'
/** 時間割の有効期間が学期の範囲外（400）。 */
export const EFFECTIVE_DATE_OUT_OF_TERM_CODE = 'TIMETABLE_040'
/** 学期が見つからない（404）。 */
export const TERM_NOT_FOUND_CODE = 'TIMETABLE_002'

export type TermApiError = ApiErrorInfo

export function parseTermApiError(error: unknown): TermApiError {
  return parseApiError(error)
}
