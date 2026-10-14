import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import OrgPermissionGroupList from '~/components/organization/OrgPermissionGroupList.vue'

/**
 * F01.2.1 8-D — OrgPermissionGroupList のスコープ非依存化（組織・チーム共用）。
 *
 *   PGL-01 組織側の従来表示（名前・説明・件数・権限タグ）が変わらない
 *   PGL-02 0 件なら空表示
 *   PGL-03 targetRole を持つグループ（チーム側）には対象ロールを出す。持たない（組織側）なら出さない
 *   PGL-04 MANAGE_ORG_AFFILIATION を含むグループに印を出す（付与できるのは ADMIN だけ・§3.2）
 */
const orgGroup = { id: 1, name: '会計係', description: '会計を扱う', permissions: ['VIEW_BILLING', 'MANAGE_BILLING'], createdAt: '2026-01-01T00:00:00Z' }

describe('OrgPermissionGroupList', () => {
  it('PGL-01', async () => {
    const w = await mountSuspended(OrgPermissionGroupList, { props: { groups: [orgGroup] } })
    expect(w.text()).toContain('会計係')
    expect(w.text()).toContain('会計を扱う')
    expect(w.find('[data-testid="group-count-1"]').text()).toContain('2')
    expect(w.text()).toContain('VIEW_BILLING')
  })

  it('PGL-02', async () => {
    const w = await mountSuspended(OrgPermissionGroupList, { props: { groups: [] } })
    expect(w.find('[data-testid="group-list-empty"]').exists()).toBe(true)
  })

  it('PGL-03', async () => {
    const team = { id: 2, name: '加盟担当', permissions: ['MANAGE_ORG_AFFILIATION'], targetRole: 'MEMBER' }
    const w = await mountSuspended(OrgPermissionGroupList, { props: { groups: [team, orgGroup] } })
    expect(w.find('[data-testid="group-target-role-2"]').exists()).toBe(true)
    expect(w.find('[data-testid="group-target-role-1"]').exists()).toBe(false)
  })

  it('PGL-04', async () => {
    const team = { id: 2, name: '加盟担当', permissions: ['MANAGE_ORG_AFFILIATION'], targetRole: 'MEMBER' }
    const w = await mountSuspended(OrgPermissionGroupList, { props: { groups: [team, orgGroup] } })
    expect(w.find('[data-testid="group-affiliation-mark-2"]').exists()).toBe(true)
    expect(w.find('[data-testid="group-affiliation-mark-1"]').exists()).toBe(false)
  })
})
