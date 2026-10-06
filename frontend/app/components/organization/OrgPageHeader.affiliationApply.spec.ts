import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { OrgDetail } from '~/composables/useOrgDetail'
import OrgPageHeader from './OrgPageHeader.vue'

/**
 * F01.2.1 8-A — 組織シェルのヘッダーに「チームとして加盟を申請」ボタンが組み込まれていること（AC-A11 の入口①）。
 * 出すか否かの判定そのものは TeamAffiliationApplyButton.spec.ts（eligibility に従う）で検証する。
 */

const stubs = {
  ProfileHeader: { template: '<div><slot /></div>' },
  FavoriteToggleButton: true,
  RoleBadge: true,
  Menu: true,
  BroadcastWizard: true,
  TeamAffiliationApplyButton: {
    props: ['orgSlug'],
    template: '<span data-testid="stub-apply-button" :data-org="orgSlug" />',
  },
}

describe('OrgPageHeader 加盟申請ボタン', () => {
  it('OH-01: 組織の slug を渡して TeamAffiliationApplyButton を描画する', async () => {
    const wrapper = await mountSuspended(OrgPageHeader, {
      props: {
        org: {
          id: 'org-a',
          numericId: 1,
          basicInfo: { name: '組織A' },
          visibility: { visibility: 'PUBLIC', supporterEnabled: false },
          metadata: { memberCount: 3 },
        } as unknown as OrgDetail,
        orgId: 'org-a',
        roleName: null,
        isAdmin: false,
        isAdminOrDeputy: false,
        followStatus: 'NONE',
        followLoading: false,
        followPermissionSyncError: false,
        joinRequestStatus: 'NONE',
        joinRequestLoading: false,
        ancestors: [],
      },
      global: { stubs },
    })
    const button = wrapper.find('[data-testid="stub-apply-button"]')
    expect(button.exists()).toBe(true)
    expect(button.attributes('data-org')).toBe('org-a')
  })
})
