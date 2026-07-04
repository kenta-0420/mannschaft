/**
 * F(マッチング) — 検索条件の自動記憶・履歴 composable
 *
 * localStorage へ「直近の検索条件」と「検索履歴（最大5件）」を保存・読み込みする。
 * ユーザーIDごとに別キーで管理し、不正なデータは自動除去する（useChatTabsPersistence 踏襲）。
 */
import type { MatchRequestSearchParams } from '~/types/matching'

const LAST_KEY_PREFIX = 'matching:search:last:'
const HISTORY_KEY_PREFIX = 'matching:search:history:'
const MAX_HISTORY = 5

/** 履歴の重複判定・要約表示に用いる検索条件キー */
const PARAM_KEYS: (keyof MatchRequestSearchParams)[] = ['prefectureCode', 'cityCode', 'category', 'keyword']

function isPlainRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/** localStorage から読み込んだ値を安全に MatchRequestSearchParams へ絞り込む */
function sanitizeParams(raw: unknown): MatchRequestSearchParams | null {
  if (!isPlainRecord(raw)) return null
  const result: MatchRequestSearchParams = {}
  for (const key of PARAM_KEYS) {
    const v = raw[key]
    if (typeof v === 'string' && v.length > 0) {
      result[key] = v as never
    }
  }
  return result
}

export function useMatchingSearchHistory() {
  function lastKey(userId: number | string): string {
    return `${LAST_KEY_PREFIX}${userId}`
  }
  function historyKey(userId: number | string): string {
    return `${HISTORY_KEY_PREFIX}${userId}`
  }

  /** 直近の検索条件を保存する */
  function saveLast(userId: number | string, params: MatchRequestSearchParams): void {
    if (!import.meta.client) return
    localStorage.setItem(lastKey(userId), JSON.stringify(params))
  }

  /** 直近の検索条件を読み込む（不正データは自動除去） */
  function loadLast(userId: number | string): MatchRequestSearchParams | null {
    if (!import.meta.client) return null
    const raw = localStorage.getItem(lastKey(userId))
    if (!raw) return null
    try {
      const sanitized = sanitizeParams(JSON.parse(raw))
      if (!sanitized) {
        localStorage.removeItem(lastKey(userId))
        return null
      }
      return sanitized
    } catch {
      localStorage.removeItem(lastKey(userId))
      return null
    }
  }

  /** 検索履歴一覧を読み込む（不正データは自動除去） */
  function loadHistory(userId: number | string): MatchRequestSearchParams[] {
    if (!import.meta.client) return []
    const raw = localStorage.getItem(historyKey(userId))
    if (!raw) return []
    try {
      const parsed: unknown = JSON.parse(raw)
      if (!Array.isArray(parsed)) {
        localStorage.removeItem(historyKey(userId))
        return []
      }
      return parsed
        .map(sanitizeParams)
        .filter((p): p is MatchRequestSearchParams => p !== null && Object.keys(p).length > 0)
        .slice(0, MAX_HISTORY)
    } catch {
      localStorage.removeItem(historyKey(userId))
      return []
    }
  }

  /** 2つの検索条件が（履歴の重複排除の観点で）同一かどうか */
  function isSameParams(a: MatchRequestSearchParams, b: MatchRequestSearchParams): boolean {
    return PARAM_KEYS.every(key => (a[key] ?? '') === (b[key] ?? ''))
  }

  /**
   * 検索履歴の先頭に条件を追加する（重複は削除してから追加・最大5件）。
   * 全条件が空（クリア相当）の場合は履歴に残さない。
   */
  function pushHistory(userId: number | string, params: MatchRequestSearchParams): void {
    if (!import.meta.client) return
    const hasCondition = PARAM_KEYS.some(key => !!params[key])
    if (!hasCondition) return
    const existing = loadHistory(userId).filter(p => !isSameParams(p, params))
    const next = [params, ...existing].slice(0, MAX_HISTORY)
    localStorage.setItem(historyKey(userId), JSON.stringify(next))
  }

  /** ユーザーの検索条件・履歴をすべて削除する */
  function clearAll(userId: number | string): void {
    if (!import.meta.client) return
    localStorage.removeItem(lastKey(userId))
    localStorage.removeItem(historyKey(userId))
  }

  return { saveLast, loadLast, loadHistory, pushHistory, clearAll, isSameParams }
}
