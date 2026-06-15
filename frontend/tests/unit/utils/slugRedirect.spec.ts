import { describe, it, expect } from 'vitest'
import { parseSlugRoute, computeSlugRedirectPath } from '~/utils/slugRedirect'
import type { SlugResolveResponse } from '~/types/slug'

/**
 * 旧 slug → 新 slug 301 リダイレクト判定ロジックのユニットテスト（BE #1542）。
 */

describe('parseSlugRoute', () => {
  it('チーム index ルートを解析する', () => {
    expect(parseSlugRoute('/teams/my-team')).toEqual({
      entity: 'teams',
      slug: 'my-team',
      rest: '',
    })
  })

  it('チームのサブパスを保持して解析する', () => {
    expect(parseSlugRoute('/teams/my-team/settings/public-settings')).toEqual({
      entity: 'teams',
      slug: 'my-team',
      rest: '/settings/public-settings',
    })
  })

  it('組織ルートを解析する', () => {
    expect(parseSlugRoute('/organizations/my-org/schedule')).toEqual({
      entity: 'organizations',
      slug: 'my-org',
      rest: '/schedule',
    })
  })

  it('対象外パスは null を返す', () => {
    expect(parseSlugRoute('/dashboard')).toBeNull()
    expect(parseSlugRoute('/villages/abc')).toBeNull()
    expect(parseSlugRoute('/teams')).toBeNull()
    expect(parseSlugRoute('/')).toBeNull()
  })

  it('URL エンコードされた slug をデコードする', () => {
    expect(parseSlugRoute('/teams/my%2Dteam')).toEqual({
      entity: 'teams',
      slug: 'my-team',
      rest: '',
    })
  })
})

describe('computeSlugRedirectPath', () => {
  const teamParts = { entity: 'teams' as const, slug: 'old-team', rest: '/settings' }
  const orgParts = { entity: 'organizations' as const, slug: 'old-org', rest: '' }

  it('MOVED のとき新 slug の同一サブパスを返す', () => {
    const result: SlugResolveResponse = { status: 'MOVED', canonicalSlug: 'new-team' }
    expect(computeSlugRedirectPath(teamParts, result)).toBe('/teams/new-team/settings')
  })

  it('組織 MOVED（サブパスなし）を返す', () => {
    const result: SlugResolveResponse = { status: 'MOVED', canonicalSlug: 'new-org' }
    expect(computeSlugRedirectPath(orgParts, result)).toBe('/organizations/new-org')
  })

  it('CURRENT は null（リダイレクト不要）', () => {
    expect(computeSlugRedirectPath(teamParts, { status: 'CURRENT' })).toBeNull()
  })

  it('NOT_FOUND は null', () => {
    expect(computeSlugRedirectPath(teamParts, { status: 'NOT_FOUND' })).toBeNull()
  })

  it('MOVED でも canonicalSlug 欠落なら null', () => {
    expect(computeSlugRedirectPath(teamParts, { status: 'MOVED' })).toBeNull()
  })
})
