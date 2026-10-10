import { describe, expect, it } from 'vitest'
import { blogPostReadRoute } from '~/utils/blogPostRoute'

/** CMP-261007-2052 AC-20/21: 記事詳細はスコープ指定が必須。スコープ無しの URL を組み立てない。 */
describe('blogPostReadRoute', () => {
  it('チーム記事は teamId を付ける', () => {
    expect(blogPostReadRoute({ content: { slug: 'a b' }, scope: { teamId: 3, organizationId: null, userId: null } }))
      .toEqual({ path: '/blog/posts/a%20b', query: { teamId: '3' } })
  })

  it('組織記事は organizationId を付ける', () => {
    expect(blogPostReadRoute({ content: { slug: 's' }, scope: { teamId: null, organizationId: 4, userId: null } }))
      .toEqual({ path: '/blog/posts/s', query: { organizationId: '4' } })
  })

  it('個人記事は個人ブログの詳細へ送る', () => {
    expect(blogPostReadRoute({ content: { slug: 's' }, scope: { teamId: null, organizationId: null, userId: 5 } }))
      .toEqual({ path: '/users/5/blog/posts/s' })
  })

  it('スコープが無い・一意でない・slug が無い記事はリンクしない', () => {
    expect(blogPostReadRoute({ content: { slug: 's' } })).toBeNull()
    expect(blogPostReadRoute({ content: { slug: 's' }, scope: { teamId: null, organizationId: null, userId: null } }))
      .toBeNull()
    expect(blogPostReadRoute({ content: { slug: 's' }, scope: { teamId: 1, organizationId: 2, userId: null } }))
      .toBeNull()
    expect(blogPostReadRoute({ content: { slug: ' ' }, scope: { teamId: 1, organizationId: null, userId: null } }))
      .toBeNull()
  })
})
