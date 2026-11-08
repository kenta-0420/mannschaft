import { describe, expect, it } from 'vitest'
import { resolveTeamAffiliationsView, hasTeamAffiliationsViewQuery } from '~/utils/teamAffiliationsView'

/**
 * F01.2.1 8-D — /teams/{slug}/affiliations のクエリ view。
 * 通知から各 view に直接着地できる（AC-L06 / AC-E10）。
 *   TAV-01 未指定・空・未知は organizations   TAV-02 既知の view はそのまま   TAV-03 配列は先頭
 */
describe('resolveTeamAffiliationsView', () => {
  it('TAV-01', () => {
    expect(resolveTeamAffiliationsView(undefined)).toBe('organizations')
    expect(resolveTeamAffiliationsView('')).toBe('organizations')
    expect(resolveTeamAffiliationsView('nope')).toBe('organizations')
  })
  it('TAV-02', () => {
    for (const v of ['organizations', 'invites', 'applications', 'restrictions']) {
      expect(resolveTeamAffiliationsView(v)).toBe(v)
    }
  })
  it('TAV-03', () => {
    expect(resolveTeamAffiliationsView(['invites', 'applications'])).toBe('invites')
  })
  it('hasTeamAffiliationsViewQuery', () => {
    expect(hasTeamAffiliationsViewQuery(undefined)).toBe(false)
    expect(hasTeamAffiliationsViewQuery('invites')).toBe(true)
  })
})
