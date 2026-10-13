import { describe, expect, it } from 'vitest'
import {
  MANAGE_ORG_AFFILIATION,
  canManageOrgAffiliation,
  canManagePermissionGroups,
  shouldLeaveGuardedTeamTab,
} from '~/utils/teamAffiliationAccess'

/**
 * F01.2.1 8-D — チームシェルの「所属組織」「権限グループ」タブの表示・滞在判定。
 *
 *   TAA-01 チーム ADMIN 割当（isScopeAdmin）は加盟操作者
 *   TAA-02 MANAGE_ORG_AFFILIATION を付与された非 ADMIN（TG）も加盟操作者
 *   TAA-03 付与の無い DEPUTY / MEMBER は操作者ではない（AC-K03 / P02）
 *   TAA-04 管理レンズ OFF では操作者として扱わない
 *   TAA-05 SYSTEM_ADMIN 単独（チーム ADMIN 割当なし）は操作者ではない
 *   TAA-06 権限グループは ADMIN かつ管理レンズ ON のときだけ
 *   TAA-07 滞在ガード: 権限確定後に条件を満たさなければ戻す / 確定前は戻さない
 */
const base = { adminLens: true, isScopeAdmin: false, roleName: 'MEMBER' as string | null, permissions: [] as string[] }

describe('canManageOrgAffiliation', () => {
  it('TAA-01', () => {
    expect(canManageOrgAffiliation({ ...base, isScopeAdmin: true, roleName: 'ADMIN' })).toBe(true)
  })
  it('TAA-02', () => {
    expect(canManageOrgAffiliation({ ...base, permissions: [MANAGE_ORG_AFFILIATION] })).toBe(true)
  })
  it('TAA-03', () => {
    expect(canManageOrgAffiliation({ ...base, roleName: 'DEPUTY_ADMIN' })).toBe(false)
    expect(canManageOrgAffiliation({ ...base, permissions: ['MANAGE_SCHEDULES'] })).toBe(false)
  })
  it('TAA-04', () => {
    expect(canManageOrgAffiliation({ ...base, adminLens: false, isScopeAdmin: true, roleName: 'ADMIN' })).toBe(false)
  })
  it('TAA-05', () => {
    expect(
      canManageOrgAffiliation({ ...base, roleName: 'SYSTEM_ADMIN', permissions: [MANAGE_ORG_AFFILIATION] }),
    ).toBe(false)
  })
})

describe('canManagePermissionGroups', () => {
  it('TAA-06', () => {
    expect(canManagePermissionGroups({ adminLens: true, isAdmin: true })).toBe(true)
    expect(canManagePermissionGroups({ adminLens: false, isAdmin: true })).toBe(false)
    expect(canManagePermissionGroups({ adminLens: true, isAdmin: false })).toBe(false)
  })
})

describe('shouldLeaveGuardedTeamTab', () => {
  const ready = { ready: true, canAffiliate: false, canGroups: false }
  it('TAA-07: affiliations は操作者でなければ戻す', () => {
    expect(shouldLeaveGuardedTeamTab({ ...ready, tab: 'affiliations' })).toBe(true)
    expect(shouldLeaveGuardedTeamTab({ ...ready, tab: 'affiliations', canAffiliate: true })).toBe(false)
  })
  it('TAA-07: permission-groups は ADMIN でなければ戻す', () => {
    expect(shouldLeaveGuardedTeamTab({ ...ready, tab: 'permission-groups' })).toBe(true)
    expect(shouldLeaveGuardedTeamTab({ ...ready, tab: 'permission-groups', canGroups: true })).toBe(false)
  })
  it('TAA-07: 権限未確定なら戻さない / 他のタブは対象外', () => {
    expect(shouldLeaveGuardedTeamTab({ ...ready, ready: false, tab: 'affiliations' })).toBe(false)
    expect(shouldLeaveGuardedTeamTab({ ...ready, tab: 'members' })).toBe(false)
  })
})
