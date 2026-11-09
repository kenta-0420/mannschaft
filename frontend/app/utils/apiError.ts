// BE の共通エラー応答（{ error: { code, message, fieldErrors } }）を画面向けに解釈する。
export interface ApiErrorInfo {
  status?: number
  code?: string
  /** 400 の fieldErrors を「項目名 → メッセージ」に直したもの（同じ項目は最初の1件）。 */
  fieldErrors: Record<string, string>
}

export function parseApiError(error: unknown): ApiErrorInfo {
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
