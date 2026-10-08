import type { BlogPostScope2 } from '~/types/cms'

/**
 * ブログ記事詳細への閲覧ルートを、記事のスコープ（チーム／組織／個人）から組み立てる。
 *
 * <p>BE の記事詳細はスコープ指定が必須である（CMP-261007-2052 AC-20/21: スコープ未指定は 404）。
 * チーム記事は `/blog/posts/{slug}?teamId=`、組織記事は `?organizationId=`、個人記事は
 * `/users/{userId}/blog/posts/{slug}` へ送る。スコープが一意に決まらない記事はリンクしない（null）。</p>
 */
export function blogPostReadRoute(post: {
  content?: { slug?: string | null } | null
  scope?: BlogPostScope2 | null
}): { path: string; query?: Record<string, string> } | null {
  const slug = post.content?.slug
  if (!slug?.trim() || !post.scope) return null
  const { teamId, organizationId, userId } = post.scope
  if ([teamId, organizationId, userId].filter(id => id != null).length !== 1) return null
  const encodedSlug = encodeURIComponent(slug)
  if (isPositiveId(teamId)) return { path: `/blog/posts/${encodedSlug}`, query: { teamId: String(teamId) } }
  if (isPositiveId(organizationId)) {
    return { path: `/blog/posts/${encodedSlug}`, query: { organizationId: String(organizationId) } }
  }
  if (isPositiveId(userId)) return { path: `/users/${userId}/blog/posts/${encodedSlug}` }
  return null
}

function isPositiveId(id: number | null | undefined): id is number {
  return typeof id === 'number' && Number.isSafeInteger(id) && id > 0
}
