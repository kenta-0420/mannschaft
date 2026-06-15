import type { SlugResolveResponse } from '~/types/slug'
import { computeSlugRedirectPath, parseSlugRoute } from '~/utils/slugRedirect'

/**
 * 旧 slug → 新 slug の 301 リダイレクト（村方式・BE #1542）。
 *
 * チーム/組織の **トップページ**（`/teams/{slug}` / `/organizations/{slug}`）に
 * `definePageMeta({ middleware: 'slug-redirect' })` で付与する名前付きミドルウェア。
 *
 * ブックマーク・被リンク・クローラが旧 slug のトップ URL に到達したとき、公開 EP
 * `GET /api/v1/public/{teams|organizations}/slug-resolve?slug=x`（permitAll・レート制限）を叩き、
 * `MOVED` なら現行 slug のトップへリダイレクトする。
 *
 * - SSR（初期ロード）では `navigateTo(..., { redirectCode: 301 })` が本物の HTTP 301 を発行する
 *   （SEO・クローラ向け）。クライアント遷移では `replace: true` で履歴を汚さず置換する。
 * - `CURRENT` / `NOT_FOUND` は何もしない（NOT_FOUND は各ページの取得 404 が従来どおり扱う）。
 *
 * グローバルにせず**トップページ限定**にしている理由: `/teams/search` 等の静的兄弟ルートや
 * `/organizations/{slug}/teams/search` などサブパスでは余計な解決リクエストを発生させないため。
 * サブパスの横断 301 対応は将来タスクとして切り出す。
 */
export default defineNuxtRouteMiddleware(async (to) => {
  const parts = parseSlugRoute(to.path)
  // 念のためのガード（このミドルウェアはトップページにのみ付与される想定）。
  if (!parts || parts.rest !== '' || to.params.slug !== parts.slug) return

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

  return navigateTo(
    { path: target, query: to.query, hash: to.hash },
    { redirectCode: 301, replace: true },
  )
})
