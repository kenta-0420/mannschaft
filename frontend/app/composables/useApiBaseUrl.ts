/**
 * API ベース URL の二層解決ユーティリティ。
 *
 * 本番は NUXT_PUBLIC_API_BASE=''（相対パス・Cloudflare 同一オリジン構成）で運用するが、
 * Nitro サーバーサイド（SSR）は相対パスではバックエンドに到達できない。
 * そのため SSR 時は NUXT_INTERNAL_API_BASE（絶対 URL）を優先し、
 * 未設定の場合のみ NUXT_PUBLIC_API_BASE にフォールバックする。
 *
 * - サーバー実行時（SSR）: NUXT_INTERNAL_API_BASE → NUXT_PUBLIC_API_BASE の順に参照
 * - クライアント実行時  : NUXT_PUBLIC_API_BASE をそのまま使用
 *
 * 設計書: docs/security/03_security_headers_and_csp.md §4.1（apiBase 二層構成）
 */
export function resolveApiBaseUrl(config: ReturnType<typeof useRuntimeConfig>): string {
  if (import.meta.server) {
    // サーバーサイドのみ利用可能な internalApiBase を参照する。
    // クライアントでは config.internalApiBase は undefined となる。
    const internal = (config as Record<string, unknown>).internalApiBase as string | undefined
    if (internal) return internal
  }
  return config.public.apiBase as string
}
