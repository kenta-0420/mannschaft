import type { SlugResolveResponse } from '~/types/slug'
import { computeSlugRedirectPath, parseSlugRoute } from '~/utils/slugRedirect'

/**
 * 旧 slug → 新 slug の 301 リダイレクトミドルウェア（村方式・BE #1542）。
 *
 * `/teams/{slug}/**` と `/organizations/{slug}/**` の全ルートを横断でカバーする。
 * ブックマーク・被リンク・クローラが旧 URL に到達したとき、公開 EP
 * `GET /api/v1/public/{teams|organizations}/slug-resolve?slug=x`（permitAll・レート制限）を叩き、
 * `MOVED` なら現行 slug の同一サブパスへリダイレクトする。
 *
 * - SSR（初期ロード）では `navigateTo(..., { redirectCode: 301 })` が本物の HTTP 301 を発行する
 *   （SEO・クローラ向け）。クライアント遷移では `replace: true` で履歴を汚さず置換する。
 * - サブパス（`/settings/public-settings` 等）は保持して新 slug へ付け替える。
 * - `CURRENT` / `NOT_FOUND` は何もしない（NOT_FOUND は各ページの取得 404 が従来どおり扱う）。
 *
 * パフォーマンス: 同一エンティティ内のサブパス遷移（slug 不変）では resolve を叩かない。
 * 新規に `[slug]` ルートへ入った初回ロード/遷移のときだけ resolve する。
 */
export default defineNuxtRouteMiddleware(async (to, from) => {
  const parts = parseSlugRoute(to.path)
  if (!parts) return

  // 同一エンティティ内のサブパス遷移（slug 不変）は resolve 不要（既に現行 slug 上にいる）。
  if (
    from
    && from.path !== to.path
    && typeof from.params.slug === 'string'
    && from.params.slug === parts.slug
  ) {
    return
  }

  const { resolveTeamSlug } = useTeamApi()
  const { resolveOrganizationSlug } = useOrganizationApi()

  let result: SlugResolveResponse
  try {
    result = parts.entity === 'teams'
      ? await resolveTeamSlug(parts.slug)
      : await resolveOrganizationSlug(parts.slug)
  }
  catch {
    // resolve 失敗（レート制限・ネットワーク等）は判定不能。リダイレクトせず通常表示に委ねる。
    return
  }

  const target = computeSlugRedirectPath(parts, result)
  if (!target) return

  // 新 slug の同一サブパスへ。クエリ/ハッシュも保持する。
  return navigateTo(
    { path: target, query: to.query, hash: to.hash },
    { redirectCode: 301, replace: true },
  )
})
