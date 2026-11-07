// 学期 API のエラー応答（{ error: { code, message, fieldErrors } }）を画面向けに解釈する。
export const TERM_HAS_TIMETABLES_CODE = 'TIMETABLE_022'
/** 学期の日付範囲が同一スコープ・同一年度の他の学期と重なる（409）。 */
export const TERM_DATE_OVERLAP_CODE = 'TIMETABLE_021'

export interface TermApiError {
  status?: number
  code?: string
  fieldErrors: Record<string, string>
}

export function parseTermApiError(error: unknown): TermApiError {
  const e = error as {
    status?: number
    statusCode?: number
    response?: { status?: number }
    data?: { error?: { code?: string; fieldErrors?: { field: string; message: string }[] } }
  }
  const body = e?.data?.error
  const fieldErrors: Record<string, string> = {}
  for (const f of body?.fieldErrors ?? []) {
    if (f?.field && !(f.field in fieldErrors)) fieldErrors[f.field] = f.message
  }
  return {
    status: e?.response?.status ?? e?.statusCode ?? e?.status,
    code: body?.code,
    fieldErrors,
  }
}
