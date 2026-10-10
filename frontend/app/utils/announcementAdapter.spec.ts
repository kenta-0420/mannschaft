// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { toAnnouncementItem, toDashboardAnnouncementItem } from './announcementAdapter'
import { blogAnnouncementScope, parseAnnouncementRouteId } from './announcementRoute'

describe('本文プレビューの一覧・元リンク契約', () => {
  it('PREVIEW-02: チームに含まれる組織配信も所有scopeと既読を維持する', () => {
    const item = toDashboardAnnouncementItem({
      id: 12, scope_type: 'ORGANIZATION', scope_id: 7, title_cache: '組織連絡',
      excerpt_cache: '要約', source_type: 'BLOG_POST', source_id: 31,
      access_state: 'FULL', content_preview_available: true, is_read: true,
      created_at: '2026-10-05T00:00:00',
    })
    expect(item).toMatchObject({ id: 12, scopeType: 'ORGANIZATION', scopeId: 7, title: '組織連絡',
      isRead: true, contentPreviewAvailable: true, sourceId: 31, sourceUrl: null })
  })

  it('PREVIEW-07/18: LOCKEDでも本文対象boolを保ち、source/URL/要約を秘匿する', () => {
    const item = toAnnouncementItem({ id: 12, scopeType: 'TEAM', scopeId: 8,
      accessState: 'LOCKED', contentPreviewAvailable: true, sourceType: 'BLOG_POST',
      sourceId: 31, sourceUrl: '/blog/posts/private?teamId=8', excerptCache: '秘密' })
    expect(item).toMatchObject({ contentPreviewAvailable: true, sourceType: null, sourceId: null,
      sourceUrl: null, excerpt: null, sourceMeta: null })
  })

  it('PREVIEW-18: 対象外はプレビューを持たず、実在する旧URLだけを保持する', () => {
    const item = toAnnouncementItem({ id: 12, scopeType: 'TEAM', scopeId: 8,
      sourceType: 'SURVEY', sourceUrl: '/surveys/example', contentPreviewAvailable: false })
    expect(item.contentPreviewAvailable).toBe(false)
    expect(item.sourceUrl).toBe('/surveys/example')
    expect(toAnnouncementItem({ id: 13, scopeType: 'TEAM', scopeId: 8, sourceType: 'SURVEY' }).sourceUrl).toBeNull()
  })

  it('PREVIEW-02: 所有scope欠落は親scopeで補わずエラーにする', () => {
    expect(() => toDashboardAnnouncementItem({ id: 12, title_cache: '連絡' })).toThrow()
  })

  it('PREVIEW-08: ブログqueryはチームか組織の内部IDを1つだけ渡す', () => {
    expect(blogAnnouncementScope({ teamId: '8' })).toEqual({ teamId: 8, organizationId: undefined })
    expect(blogAnnouncementScope({ organizationId: '7' })).toEqual({ teamId: undefined, organizationId: 7 })
    expect(blogAnnouncementScope({})).toEqual({ teamId: undefined, organizationId: undefined })
    expect(() => blogAnnouncementScope({ teamId: '8', organizationId: '7' })).toThrow()
  })

  it.each(['0', '-1', '1.5', '9007199254740992', ['8', '9'], null])('PREVIEW-08: 不正なquery ID(%s)は詳細APIに渡さない', (value) => {
    expect(() => parseAnnouncementRouteId(value)).toThrow()
  })

  it('PREVIEW-08: 掲示板threadIdの正の安全整数と省略を受け付ける', () => {
    expect(parseAnnouncementRouteId('31')).toBe(31)
    expect(parseAnnouncementRouteId(undefined)).toBeUndefined()
  })
})
