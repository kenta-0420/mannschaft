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
 *   TAA-05 SYSTEM_ADMIN 兼任でも、対象チームで MANAGE_ORG_AFFILIATION を保有していれば操作者（BE は権限保有で判定）。
 *          SYSTEM_ADMIN 単独（ADMIN 割当も権限も無い）は操作者ではない。roleName には依存しない
 *   TAA-06 権限グループは「チーム ADMIN 割当（isScopeAdmin）」かつ管理レンズ ON のときだけ。
 *          SYSTEM_ADMIN のみ（ADMIN 割当なし）には出さない（一覧 API が 403 のため）
 *   TAA-07 滞在ガード: 権限確定後に条件を満たさなければ戻す / 確定前は戻さない
 */
const base = { adminLens: true, isScopeAdmin: false, permissions: [] as string[] }

describe('canManageOrgAffiliation', () => {
  it('TAA-01', () => {
    expect(canManageOrgAffiliation({ ...base, isScopeAdmin: true })).toBe(true)
  })
  it('TAA-02', () => {
    expect(canManageOrgAffiliation({ ...base, permissions: [MANAGE_ORG_AFFILIATION] })).toBe(true)
  })
  it('TAA-03', () => {
    expect(canManageOrgAffiliation({ ...base })).toBe(false)
    expect(canManageOrgAffiliation({ ...base, permissions: ['MANAGE_SCHEDULES'] })).toBe(false)
  })
  it('TAA-04', () => {
    expect(canManageOrgAffiliation({ ...base, adminLens: false, isScopeAdmin: true })).toBe(false)
  })
  it('TAA-05: SYSTEM_ADMIN 兼任でも権限を保有していれば操作者。権限が無ければ操作者ではない', () => {
    // roleName が SYSTEM_ADMIN（優先して返る）でも、permissions に権限があれば true。入力に roleName は無い
    expect(canManageOrgAffiliation({ ...base, permissions: [MANAGE_ORG_AFFILIATION] })).toBe(true)
    expect(canManageOrgAffiliation({ ...base, permissions: ['MANAGE_SCHEDULES'] })).toBe(false)
  })
})

describe('canManagePermissionGroups', () => {
  it('TAA-06', () => {
    expect(canManagePermissionGroups({ adminLens: true, isScopeAdmin: true })).toBe(true)
    expect(canManagePermissionGroups({ adminLens: false, isScopeAdmin: true })).toBe(false)
    // SYSTEM_ADMIN のみ（チーム ADMIN 割当なし）
    expect(canManagePermissionGroups({ adminLens: true, isScopeAdmin: false })).toBe(false)
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
