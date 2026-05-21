/**
 * F19.1 Phase 3 — 公開ページ共通 SEO composable。
 *
 * hreflang (6言語 + x-default)、JSON-LD、canonical を一括設定する。
 * 既存の useSeoMeta() とは独立して動作し、追加のタグとして注入する。
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §9.2
 */
export const useSeoPublicPage = (options: {
  /** canonical パス（例: /public/teams/123）。reactive にするため Ref または関数も受け付ける */
  canonicalPath: string | (() => string)
  title: string | (() => string)
  description: string | (() => string)
  imageUrl?: string | (() => string | null | undefined)
  /** JSON-LD オブジェクト（Organization または Article スキーマ）*/
  jsonLd?: Record<string, unknown> | (() => Record<string, unknown> | undefined)
}) => {
  const config = useRuntimeConfig()

  /**
   * フロントエンドのベース URL を解決する。
   * apiBase = http://localhost:8080/api/v1 → http://localhost:8080
   * NUXT_PUBLIC_BASE_URL が設定されている場合はそちらを優先する。
   */
  const resolvedBaseUrl = computed((): string => {
    const explicit = (config.public as Record<string, unknown>).baseUrl as string | undefined
    if (explicit) return explicit.replace(/\/$/, '')
    return String(config.public.apiBase).replace(/\/api\/v1$/, '')
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
    return typeof options.jsonLd === 'function' ? options.jsonLd() : options.jsonLd
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
          children: JSON.stringify(ld),
          key: 'json-ld',
        },
      ]
    }),
  })

  return { canonicalUrl }
}
