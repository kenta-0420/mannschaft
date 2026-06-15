import type { SlugResolveResponse } from '~/types/slug'

/**
 * 旧 slug → 新 slug 301 リダイレクトの純粋な判定ロジック（村方式・BE #1542）。
 *
 * Nuxt ランタイムに依存しない純関数として切り出し、ミドルウェアから呼ぶ。
 * これにより resolve 結果に対する遷移先計算をユニットテストできる。
 */

/** `/teams/{slug}/**` または `/organizations/{slug}/**` を解析した結果。 */
export interface SlugRouteParts {
  entity: 'teams' | 'organizations'
  slug: string
  /** slug 以降のサブパス（先頭スラッシュ付き、無ければ空文字）。 */
  rest: string
}

/**
 * パスがチーム/組織の slug ルートかを判定し、構成要素を返す。対象外は null。
 *
 * @param path ルートパス（クエリ・ハッシュを含まない）
 */
export function parseSlugRoute(path: string): SlugRouteParts | null {
  const match = /^\/(teams|organizations)\/([^/]+)(\/.*)?$/.exec(path)
  if (!match) return null
  const slug = decodeURIComponent(match[2] ?? '')
  if (!slug) return null
  return {
    entity: match[1] as 'teams' | 'organizations',
    slug,
    rest: match[3] ?? '',
  }
}

/**
 * resolve 結果から 301 リダイレクト先パスを計算する。
 *
 * - `MOVED` かつ `canonicalSlug` ありのときだけ、新 slug の同一サブパスを返す
 * - `CURRENT` / `NOT_FOUND` / canonicalSlug 欠落のときは null（リダイレクト不要）
 *
 * @param parts  parseSlugRoute の結果
 * @param result slug-resolve のレスポンス
 * @returns 遷移先パス（例: `/teams/new-slug/settings`）、または null
 */
export function computeSlugRedirectPath(
  parts: SlugRouteParts,
  result: SlugResolveResponse,
): string | null {
  if (result.status !== 'MOVED' || !result.canonicalSlug) return null
  return `/${parts.entity}/${result.canonicalSlug}${parts.rest}`
}
