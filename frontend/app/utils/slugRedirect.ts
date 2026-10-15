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

/**
 * 旧 slug → 新 slug の 301 リダイレクト全体を判定する純粋ロジック。
 *
 * パス解析（parseSlugRoute）と解決呼び出し（注入された resolve 関数）と
 * 遷移先計算（computeSlugRedirectPath）を 1 つにまとめ、ランタイム非依存で
 * テストできる形にしている。SSR ミドルウェアとページ側フォールバックの双方が
 * この関数を共有することで、解決ロジックの二重化を防ぐ（BE #1542・村方式）。
 *
 * - 対象外パス（teams/organizations 以外）→ null（処理不要）
 * - `CURRENT` / `NOT_FOUND` / canonicalSlug 欠落 → null（リダイレクト不要）
 * - `MOVED` かつ canonicalSlug あり → 新 slug の同一サブパス文字列
 *
 * 解決呼び出しが失敗した場合（ネットワークエラー等）は null を返し、
 * 呼び出し元は通常のページ表示（最終的に 404）にフォールバックする。
 *
 * @param path     現在のルートパス（クエリ・ハッシュを含まない）
 * @param resolve  entity と slug を受け取り SlugResolveResponse を返す解決関数
 * @returns 遷移先パス（例: `/teams/new-slug/settings`）、または null
 */
export async function resolveSlugRedirectPath(
  path: string,
  resolve: (entity: 'teams' | 'organizations', slug: string) => Promise<SlugResolveResponse>,
): Promise<string | null> {
  const parts = parseSlugRoute(path)
  if (!parts) return null
  try {
    const result = await resolve(parts.entity, parts.slug)
    return computeSlugRedirectPath(parts, result)
  } catch {
    // 解決失敗を沈黙させると一時的な不通が恒久 404 化する。SSR ログに残して検知可能にする（挙動は null フォールバック維持）。
    console.warn('[slugRedirect] slug 解決に失敗しました', { path })
    return null
  }
}
