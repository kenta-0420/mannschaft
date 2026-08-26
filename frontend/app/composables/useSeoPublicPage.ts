import { escapeJsonLdForHtml } from '~/utils/escapeJsonLdForHtml'

/**
 * F19.1 Phase 3 — 公開ページ共通 SEO composable。
 *
 * hreflang (6言語 + x-default)、JSON-LD、canonical を一括設定する。
 * 既存の useSeoMeta() とは独立して動作し、追加のタグとして注入する。
 *
 * F21.1 GEO:
 *  - canonical / baseUrl の算出を本 composable に一元化（単一ソース化）。
 *    呼び出し側は戻り値 canonicalUrl / baseUrl を受け取り JSON-LD・OGP に流用する
 *    （ページ側で独自に apiBase からホストを組み立てるとホスト不整合が起きるため）。
 *  - jsonLd は ctx（canonicalUrl / baseUrl）を受け取る関数も渡せるよう拡張（後方互換）。
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §9.2 /
 *         docs/features/F21.1_geo_optimization.md §4 / §6 / §7
 */

/** jsonLd 関数に渡す解決済みコンテキスト（canonical / baseUrl の単一ソース）。 */
export interface SeoJsonLdContext {
  /** 当該ページの絶対 canonical URL（例: https://host/public/teams/123）*/
  canonicalUrl: string
  /** フロントエンドのベース URL（例: https://host）。BreadcrumbList の item に使う。 */
  baseUrl: string
}

export const useSeoPublicPage = (options: {
  /** canonical パス（例: /public/teams/123）。reactive にするため Ref または関数も受け付ける */
  canonicalPath: string | (() => string)
  title: string | (() => string)
  description: string | (() => string)
  imageUrl?: string | (() => string | null | undefined)
  /**
   * JSON-LD オブジェクト（Organization / Article / @graph スキーマ）。
   * - オブジェクト直渡し or それを返す引数なし関数（後方互換）
   * - F21.1: ctx（canonicalUrl / baseUrl）を受け取り Record または undefined を返す関数
   */
  jsonLd?:
    | Record<string, unknown>
    | ((ctx?: SeoJsonLdContext) => Record<string, unknown> | undefined)
}) => {
  const config = useRuntimeConfig()
  // useRequestURL() は SSR/CSR 両対応の Nuxt 標準 composable。
  // リクエスト Host ヘッダーから origin を取得するため、NUXT_PUBLIC_BASE_URL 未設定でも
  // 絶対 URL を確実に得るための最終フォールバックとして使用する。
  const requestUrl = useRequestURL()

  /**
   * フロントエンドのベース URL を解決する（優先順位）。
   * 1. NUXT_PUBLIC_BASE_URL（環境変数で明示した絶対 URL）
   * 2. NUXT_PUBLIC_API_BASE から /api/v1 を除去したホスト（dev 環境向け互換）
   * 3. useRequestURL().origin（SSR リクエストの Host ヘッダー由来の絶対 URL）
   *
   * NUXT_PUBLIC_API_BASE=''（本番同一オリジン構成）では 2 が '' になるため
   * NUXT_PUBLIC_BASE_URL を明示するか 3 のフォールバックが機能する。
   * 設計書: docs/security/03_security_headers_and_csp.md §4.1
   */
  const resolvedBaseUrl = computed((): string => {
    // 1. NUXT_PUBLIC_BASE_URL
    const explicit = config.public.baseUrl as string | undefined
    if (explicit) return explicit.replace(/\/$/, '')
    // 2. apiBase から /api/v1 を除去（dev: http://localhost:8080）
    const fromApiBase = String(config.public.apiBase).replace(/\/api\/v1$/, '')
    if (fromApiBase) return fromApiBase
    // 3. リクエスト Host 由来の origin（''・空文字フォールバック）
    return requestUrl.origin
  })

  const resolvedPath = computed((): string => {
    const raw = typeof options.canonicalPath === 'function'
      ? options.canonicalPath()
      : options.canonicalPath
    return raw
  })

  const canonicalUrl = computed((): string => `${resolvedBaseUrl.value}${resolvedPath.value}`)

  const resolvedJsonLd = computed((): Record<string, unknown> | undefined => {
    if (!options.jsonLd) return undefined
    if (typeof options.jsonLd === 'function') {
      // F21.1: ctx（canonicalUrl / baseUrl）を渡す。引数なしの旧シグネチャ関数は ctx を無視する。
      return options.jsonLd({ canonicalUrl: canonicalUrl.value, baseUrl: resolvedBaseUrl.value })
    }
    return options.jsonLd
  })

  useHead({
    link: computed(() => [
      // canonical
      { rel: 'canonical', href: canonicalUrl.value },
      // hreflang 6言語 + x-default
      { rel: 'alternate', hreflang: 'ja', href: `${canonicalUrl.value}?lang=ja` },
      { rel: 'alternate', hreflang: 'en', href: `${canonicalUrl.value}?lang=en` },
      { rel: 'alternate', hreflang: 'zh', href: `${canonicalUrl.value}?lang=zh` },
      { rel: 'alternate', hreflang: 'ko', href: `${canonicalUrl.value}?lang=ko` },
      { rel: 'alternate', hreflang: 'es', href: `${canonicalUrl.value}?lang=es` },
      { rel: 'alternate', hreflang: 'de', href: `${canonicalUrl.value}?lang=de` },
      { rel: 'alternate', hreflang: 'x-default', href: canonicalUrl.value },
    ]),
    script: computed(() => {
      const ld = resolvedJsonLd.value
      if (!ld) return []
      return [
        {
          type: 'application/ld+json',
          // F21.1 セキュリティ（XSS 対策）: JSON 文字列中の小なり記号を `<` に
          // 置換してから注入する。unhead は children を自動エスケープしないため、
          // philosophy / name / city 等の自由入力に閉じスクリプトタグ文字列が混入すると
          // スクリプトブレイクアウト（XSS）が起きる。公開ページは未認証アクセス可で影響大。
          children: escapeJsonLdForHtml(JSON.stringify(ld)),
          key: 'json-ld',
        },
      ]
    }),
  })

  // F21.1: canonical / baseUrl を単一ソースとして呼び出し側へ露出する。
  // BreadcrumbList の item や Organization.url / @id、OGP の ogUrl に流用する。
  return { canonicalUrl, baseUrl: resolvedBaseUrl }
}
