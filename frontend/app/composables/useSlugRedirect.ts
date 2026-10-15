import type { SlugResolveResponse } from '~/types/slug'
import { resolveApiBaseUrl } from '~/composables/useApiBaseUrl'

/**
 * 旧 slug → 新 slug の 301 リダイレクト解決を担う共通 composable（BE #1542・村方式）。
 *
 * SSR ミドルウェア（slug-redirect.global）とページ側フォールバックの双方から呼ばれ、
 * 解決ロジックの二重化を防ぐ。
 *
 * 公開解決 EP `GET /api/v1/public/(teams|organizations)/slug-resolve?slug=x` は
 * permitAll（認証不要）なので、認可ヘッダや Pinia ストアに依存する {@link useApi} は使わず、
 * baseURL だけを解決した素の `$fetch` で叩く。これにより SSR ミドルウェアの軽量な
 * 実行コンテキスト（authStore 等が確立する前）でも安全に呼べる。
 *
 * スコープ漏洩防止のため EP は名前など実データを返さず status / canonicalSlug のみを返す。
 */
export function useSlugRedirect() {
  const config = useRuntimeConfig()
  const baseURL = resolveApiBaseUrl(config)

  /**
   * 指定 entity / slug を解決する。
   *
   * - CURRENT: 現行 slug（リダイレクト不要）
   * - MOVED: 旧 slug → canonicalSlug へ 301 すべき
   * - NOT_FOUND: 該当なし
   */
  async function resolveSlug(
    entity: 'teams' | 'organizations',
    slug: string,
  ): Promise<SlugResolveResponse> {
    const query = new URLSearchParams({ slug })
    return $fetch<SlugResolveResponse>(
      `/api/v1/public/${entity}/slug-resolve?${query}`,
      { baseURL },
    )
  }

  return { resolveSlug }
}
