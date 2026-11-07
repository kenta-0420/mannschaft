import { describe, expect, it } from 'vitest'
import { hasViewQuery, resolveMemberTeamsView } from '~/utils/memberTeamsView'

/**
 * F01.2.1 8-B — member-teams の view 解決（AC-K02: 権限のない直打ちは一覧へ戻す）。
 */
describe('resolveMemberTeamsView', () => {
  it.each(['applications', 'invites', 'restrictions', 'groups'])('管理権限があれば %s をそのまま返す', (view) => {
    expect(resolveMemberTeamsView(view, true)).toBe(view)
  })

  it.each(['applications', 'invites', 'restrictions', 'groups'])('管理権限がなければ %s でも一覧へ戻す', (view) => {
    expect(resolveMemberTeamsView(view, false)).toBe('teams')
  })

  it('未指定・未知の値・空文字は、権限があっても一覧', () => {
    expect(resolveMemberTeamsView(undefined, true)).toBe('teams')
    expect(resolveMemberTeamsView('foo', true)).toBe('teams')
    expect(resolveMemberTeamsView('', true)).toBe('teams')
    expect(resolveMemberTeamsView('teams', true)).toBe('teams')
  })

  it('配列クエリ（?view=a&view=b）は先頭を採用する', () => {
    expect(resolveMemberTeamsView(['invites', 'applications'], true)).toBe('invites')
    expect(resolveMemberTeamsView(['invites'], false)).toBe('teams')
  })
})

describe('hasViewQuery', () => {
  it('view が付いていれば true、無い・空なら false', () => {
    expect(hasViewQuery('applications')).toBe(true)
    expect(hasViewQuery('foo')).toBe(true)
    expect(hasViewQuery(['invites'])).toBe(true)
    expect(hasViewQuery(undefined)).toBe(false)
    expect(hasViewQuery(null)).toBe(false)
    expect(hasViewQuery('')).toBe(false)
  })
})
