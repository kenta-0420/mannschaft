import { describe, it, expect, vi } from 'vitest'
import { parseSlugRoute, computeSlugRedirectPath, resolveSlugRedirectPath } from '~/utils/slugRedirect'
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

describe('resolveSlugRedirectPath', () => {
  it('MOVED のとき新 slug の同一サブパスを返し、entity/slug で resolve を呼ぶ', async () => {
    const resolve = vi.fn(async (): Promise<SlugResolveResponse> => ({
      status: 'MOVED',
      canonicalSlug: 'new-team',
    }))
    const target = await resolveSlugRedirectPath('/teams/old-team/settings', resolve)
    expect(target).toBe('/teams/new-team/settings')
    expect(resolve).toHaveBeenCalledWith('teams', 'old-team')
  })

  it('組織のサブパス・末尾を保持して付け替える', async () => {
    const resolve = vi.fn(async (): Promise<SlugResolveResponse> => ({
      status: 'MOVED',
      canonicalSlug: 'new-org',
    }))
    const target = await resolveSlugRedirectPath('/organizations/old-org/schedule/list', resolve)
    expect(target).toBe('/organizations/new-org/schedule/list')
    expect(resolve).toHaveBeenCalledWith('organizations', 'old-org')
  })

  it('CURRENT（現行 slug）は null を返しリダイレクトしない（happy-path 非干渉）', async () => {
    const resolve = vi.fn(async (): Promise<SlugResolveResponse> => ({ status: 'CURRENT' }))
    expect(await resolveSlugRedirectPath('/teams/my-team', resolve)).toBeNull()
  })

  it('NOT_FOUND は null を返す（ページ側の 404 表示を継続）', async () => {
    const resolve = vi.fn(async (): Promise<SlugResolveResponse> => ({ status: 'NOT_FOUND' }))
    expect(await resolveSlugRedirectPath('/teams/ghost', resolve)).toBeNull()
  })

  it('対象外パスは resolve を呼ばずに null を返す', async () => {
    const resolve = vi.fn(async (): Promise<SlugResolveResponse> => ({ status: 'CURRENT' }))
    expect(await resolveSlugRedirectPath('/dashboard', resolve)).toBeNull()
    expect(resolve).not.toHaveBeenCalled()
  })

  it('解決呼び出しが失敗しても例外を投げず null を返す（フォールバック）', async () => {
    const resolve = vi.fn(async (): Promise<SlugResolveResponse> => {
      throw new Error('network error')
    })
    expect(await resolveSlugRedirectPath('/teams/old-team', resolve)).toBeNull()
  })

  it('クエリ文字列を含まないパス前提で slug を正しく抽出する', async () => {
    const resolve = vi.fn(async (): Promise<SlugResolveResponse> => ({
      status: 'MOVED',
      canonicalSlug: 'new-team',
    }))
    const target = await resolveSlugRedirectPath('/teams/old-team', resolve)
    expect(target).toBe('/teams/new-team')
  })
})
