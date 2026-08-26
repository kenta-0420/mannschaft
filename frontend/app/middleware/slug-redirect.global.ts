import { parseSlugRoute, resolveSlugRedirectPath } from '~/utils/slugRedirect'

/**
 * 旧 slug → 新 slug の「SSR 本物 301」を横断（サブパス含む）で実現するグローバルミドルウェア
 * （BE #1542・村方式 / 統合: #1545 のページ側 onMounted 解決を SSR にも拡張）。
 *
 * ## なぜ middleware か
 * `/teams/[slug]/**` `/organizations/[slug]/**` のサブページ（settings 等）は多数あり、
 * かつ layouts ディレクトリが存在しない（デフォルトレイアウトのみ）。グローバルミドルウェアなら
 * パスを見るだけで全サブパスを一括カバーでき、ページごとの definePageMeta 付与も不要。
 *
 * ## happy-path 非干渉の担保（#1545 の罠の回避）
 * - **SSR の初回ナビゲーションでのみ**動作する（`import.meta.server`）。
 *   クライアント側の SPA 遷移（カードクリック等）では即 return し、解決 EP を一切叩かない。
 *   → アプリ内遷移のパフォーマンス・挙動には一切干渉しない。
 * - teams / organizations 以外のパスは `parseSlugRoute` が null を返すので即 return。
 * - 解決 EP は `CURRENT`（現行 slug）のとき何もせず通過する。`MOVED` のときだけ 301。
 *   現行 slug の正常表示には余計なリダイレクトを挟まない。
 *
 * ## 本物の HTTP 301 になる理由
 * Nuxt のルートミドルウェアが **SSR 実行時に** `navigateTo(target, { redirectCode: 301 })`
 * を返すと、Nitro が実際の HTTP 301 レスポンス（Location ヘッダ付き）を返す。
 * クローラ・ブックマークの SEO（リンクエクイティ継承）を保全できる。
 *
 * ## サブパス・クエリ・ハッシュの保持
 * `resolveSlugRedirectPath` が `parts.rest`（slug 以降のサブパス）を保持して新 slug に付け替え、
 * クエリ・ハッシュは `to.query` / `to.hash` をそのまま引き継ぐ。
 */
export default defineNuxtRouteMiddleware(async (to) => {
  // クライアント側 SPA 遷移では一切動作しない（happy-path 非干渉の要）。
  // 本物の 301 を返せるのは SSR 実行時のみで、クライアントで叩くと余計な解決呼び出しになる。
  if (!import.meta.server) return

  // teams / organizations の slug ルート以外は対象外（即通過）。
  if (!parseSlugRoute(to.path)) return

  const { resolveSlug } = useSlugRedirect()
  const target = await resolveSlugRedirectPath(to.path, resolveSlug)
  if (!target) return

  // SSR 実行時の navigateTo + redirectCode: 301 → 本物の HTTP 301。
  // サブパスは target に含まれ、クエリ・ハッシュは引き継ぐ。
  return navigateTo(
    { path: target, query: to.query, hash: to.hash },
    { redirectCode: 301, replace: true },
  )
})
