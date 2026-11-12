/**
 * チャット API 用の URLSearchParams ビルダー（内部ヘルパ）。
 * undefined / null の値はクエリに含めない。
 */
export function mapCursorMeta(meta?: { nextCursor?: string | null; hasNext?: boolean }): {
  nextCursor: string | null
  hasMore: boolean
} {
  // BE の CursorMeta は hasNext。FE の呼び出し側は hasMore で扱うため写す。
  return { nextCursor: meta?.nextCursor ?? null, hasMore: meta?.hasNext ?? false }
}

export function buildQuery(params: Record<string, unknown>): string {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null) {
      query.set(key, String(value))
    }
  }
  return query.toString()
}
