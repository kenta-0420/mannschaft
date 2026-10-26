/**
 * R2 オブジェクトキーから再生・表示用 URL を解決する composable。
 * 現フェーズでは API プロキシ経由の URL を返す（後のフェーズで Workers ドメイン対応予定）。
 */
export function useR2Url() {
  /**
   * R2 オブジェクトキーをプロキシ経由の URL に変換する。
   * @param key R2 オブジェクトキー
   * @returns プロキシ経由の URL
   */
  function resolveUrl(key: string): string {
    return `/api/r2/${encodeURIComponent(key)}`
  }

  return { resolveUrl }
}
