export type LoadErrorKind =
  | 'generic'
  | 'forbidden'
  | 'notFoundOrForbidden'
  | 'network'
  | 'server'

type ErrorWithHttpStatus = {
  status?: number
  statusCode?: number
  response?: { status?: number }
  name?: string
  message?: string
}

/** ofetch 等が投げるエラーから HTTP ステータスを取り出す。 */
export function getLoadErrorHttpStatus(error: unknown): number | undefined {
  if (!error || typeof error !== 'object') return undefined
  const candidate = error as ErrorWithHttpStatus
  return candidate.statusCode ?? candidate.status ?? candidate.response?.status
}

/**
 * 取得失敗を利用者向けの表示種別へ変換する。
 * 404 はリソース存在の漏えいを避けるため、常に「不存在または表示範囲外」として扱う。
 */
export function classifyLoadError(error: unknown): LoadErrorKind {
  const status = getLoadErrorHttpStatus(error)
  if (status === 403) return 'forbidden'
  if (status === 404) return 'notFoundOrForbidden'
  if (status !== undefined && status >= 500) return 'server'
  if (status !== undefined) return 'generic'

  if (error && typeof error === 'object') {
    const candidate = error as ErrorWithHttpStatus
    if (
      candidate.name === 'FetchError'
      || candidate.name === 'TypeError'
      || /failed to fetch|fetch failed|network|internet|offline/i.test(candidate.message ?? '')
    ) {
      return 'network'
    }
  }

  return 'generic'
}
