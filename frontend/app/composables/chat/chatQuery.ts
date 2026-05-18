/**
 * チャット API 用の URLSearchParams ビルダー（内部ヘルパ）。
 * undefined / null の値はクエリに含めない。
 */
export function buildQuery(params: Record<string, unknown>): string {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null) {
      query.set(key, String(value))
    }
  }
  return query.toString()
}
