import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { TeamResponse } from '~/types/team'
import type { FollowUiStatus } from '~/composables/useFollowSelfStatus'
import TeamPageHeader from './TeamPageHeader.vue'

/**
 * フォロー解除・退出表示の受け入れ条件テスト（チームヘッダー・CMP-261001-0835）。
 * `OrgPageHeader.followLeave.spec.ts` と同型（組織・チーム双方が対象 AC）。
 */

const MenuStub = {
  props: ['model'],
  template: '<div data-testid="overflow-menu-stub" />',
}

const stubs = {
  ProfileHeader: { template: '<div><slot /></div>' },
  FavoriteToggleButton: true,
  RoleBadge: true,
  Tag: true,
  Menu: MenuStub,
  BroadcastWizard: true,
  Button: {
    props: ['label', 'loading', 'disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

function makeTeam(supporterEnabled: boolean): TeamResponse {
  return {
    id: 1,
    numericId: 1,
    slug: 'team-a',
    visibility: { visibility: 'PRIVATE', supporterEnabled },
    metadata: { memberCount: 3 },
    location: { template: 'default' },
  } as unknown as TeamResponse
}

interface MountOpts {
  roleName: string | null
  isAdmin?: boolean
  followStatus: FollowUiStatus
  followPermissionSyncError?: boolean
  supporterEnabled?: boolean
}

async function mountHeader(opts: MountOpts) {
  return mountSuspended(TeamPageHeader, {
    props: {
      team: makeTeam(opts.supporterEnabled ?? false),
      displayName: 'チームA',
      roleName: opts.roleName,
      isAdmin: opts.isAdmin ?? false,
      isAdminOrDeputy: opts.isAdmin ?? false,
      followStatus: opts.followStatus,
      followLoading: false,
      followPermissionSyncError: opts.followPermissionSyncError ?? false,
      joinRequestStatus: 'UNKNOWN',
      joinRequestLoading: false,
      templateLabel: {},
    },
    global: { stubs },
  })
}

describe('TeamPageHeader フォロー解除・退出（CMP-261001-0835）', () => {
  // AC-1
  it('AC-1: SUPPORTER ロール・supporterEnabled=false でも APPROVED なら「フォロー解除」導線が出る', async () => {
    const wrapper = await mountHeader({ roleName: 'SUPPORTER', followStatus: 'APPROVED', supporterEnabled: false })
    const button = wrapper.find('[data-testid="follow-unfollow-button"]')
    expect(button.exists()).toBe(true)

    await button.trigger('click')
    expect(wrapper.emitted('showCancelConfirm')).toHaveLength(1)
  })

  it('AC-1: roleName=null でも APPROVED なら「フォロー解除」導線が出る', async () => {
    const wrapper = await mountHeader({ roleName: null, followStatus: 'APPROVED' })
    expect(wrapper.find('[data-testid="follow-unfollow-button"]').exists()).toBe(true)
  })

  // AC-2
  it('AC-2: SUPPORTER には退出ボタン（インライン）が出ない', async () => {
    const wrapper = await mountHeader({ roleName: 'SUPPORTER', isAdmin: false, followStatus: 'APPROVED' })
    expect(wrapper.find('[data-testid="team-leave-button"]').exists()).toBe(false)
  })

  it('AC-2: SUPPORTER には⋯メニューにも退出項目が無い', async () => {
    const wrapper = await mountHeader({ roleName: 'SUPPORTER', isAdmin: false, followStatus: 'APPROVED' })
    const menu = wrapper.findComponent(MenuStub)
    const items = (menu.props('model') ?? []) as { label: string }[]
    expect(items.some(i => i.label === 'チームから退出')).toBe(false)
  })

  // AC-3
  it('AC-3: MEMBER(非ADMIN) には退出が出る', async () => {
    const wrapper = await mountHeader({ roleName: 'MEMBER', isAdmin: false, followStatus: 'NONE' })
    expect(wrapper.find('[data-testid="team-leave-button"]').exists()).toBe(true)
  })

  it('AC-3: MEMBER にはフォロー系ボタンが出ない', async () => {
    const wrapper = await mountHeader({ roleName: 'MEMBER', isAdmin: false, followStatus: 'NONE', supporterEnabled: true })
    expect(wrapper.find('[data-testid="follow-apply-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="follow-unfollow-button"]').exists()).toBe(false)
  })

  it('AC-3: ADMIN には退出が出ない', async () => {
    const wrapper = await mountHeader({ roleName: 'ADMIN', isAdmin: true, followStatus: 'NONE' })
    expect(wrapper.find('[data-testid="team-leave-button"]').exists()).toBe(false)
  })

  // AC-4
  it('AC-4: NONE・roleName無し・supporterEnabled=true なら「フォローする」が出る', async () => {
    const wrapper = await mountHeader({ roleName: null, followStatus: 'NONE', supporterEnabled: true })
    const button = wrapper.find('[data-testid="follow-apply-button"]')
    expect(button.exists()).toBe(true)
    await button.trigger('click')
    expect(wrapper.emitted('applySupporter')).toHaveLength(1)
  })

  it('AC-4: NONE でも supporterEnabled=false なら「フォローする」は出ない', async () => {
    const wrapper = await mountHeader({ roleName: null, followStatus: 'NONE', supporterEnabled: false })
    expect(wrapper.find('[data-testid="follow-apply-button"]').exists()).toBe(false)
  })

  it('AC-4: PENDING なら「申請取消」導線が出る', async () => {
    const wrapper = await mountHeader({ roleName: null, followStatus: 'PENDING', supporterEnabled: true })
    const button = wrapper.find('[data-testid="follow-pending-cancel-button"]')
    expect(button.exists()).toBe(true)
    await button.trigger('click')
    expect(wrapper.emitted('cancelSupporter')).toHaveLength(1)
  })

  // 検分修繕: MEMBER/ADMIN 等の正規所属ロールに PENDING 申請が併存しても「取消」は出さない
  // （BE はこの PENDING を解除対象として扱わないため、取消ボタンを出すと誤操作導線になる）。
  it('検分修繕: PENDING でも MEMBER の正規所属があれば「取消」導線を出さない', async () => {
    const wrapper = await mountHeader({ roleName: 'MEMBER', followStatus: 'PENDING', supporterEnabled: true })
    expect(wrapper.find('[data-testid="follow-pending-cancel-button"]').exists()).toBe(false)
  })

  it('検分修繕: PENDING でも ADMIN の正規所属があれば「取消」導線を出さない', async () => {
    const wrapper = await mountHeader({ roleName: 'ADMIN', isAdmin: true, followStatus: 'PENDING', supporterEnabled: true })
    expect(wrapper.find('[data-testid="follow-pending-cancel-button"]').exists()).toBe(false)
  })

  it('検分修繕: PENDING で roleName=SUPPORTER のときは「取消」導線が出る', async () => {
    const wrapper = await mountHeader({ roleName: 'SUPPORTER', followStatus: 'PENDING', supporterEnabled: true })
    expect(wrapper.find('[data-testid="follow-pending-cancel-button"]').exists()).toBe(true)
  })

  // AC台帳の不足分: AC-4 で visibility 自体が未設定（null/undefined）でも例外なく「フォローする」を出さない。
  it('AC-4 不足分: team.visibility が未設定でも「フォローする」は出ない（例外も発生しない）', async () => {
    const wrapper = await mountSuspended(TeamPageHeader, {
      props: {
        team: { id: 1, numericId: 1, slug: 'team-a', metadata: { memberCount: 3 }, location: { template: 'default' } } as unknown as TeamResponse,
        displayName: 'チームA',
        roleName: null,
        isAdmin: false,
        isAdminOrDeputy: false,
        followStatus: 'NONE',
        followLoading: false,
        followPermissionSyncError: false,
        joinRequestStatus: 'UNKNOWN',
        joinRequestLoading: false,
        templateLabel: {},
      },
      global: { stubs },
    })
    expect(wrapper.find('[data-testid="follow-apply-button"]').exists()).toBe(false)
  })

  // AC-6
  it.each(['UNKNOWN', 'LOADING'] as const)('AC-6: %s の間は「フォローする」もエラー表示も出ない', async (status) => {
    const wrapper = await mountHeader({ roleName: null, followStatus: status, supporterEnabled: true })
    expect(wrapper.find('[data-testid="follow-apply-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="follow-fetch-error"]').exists()).toBe(false)
  })

  it('AC-6: ERROR のときはエラー表示と再試行ボタンが出る', async () => {
    const wrapper = await mountHeader({ roleName: null, followStatus: 'ERROR', supporterEnabled: true })
    expect(wrapper.find('[data-testid="follow-fetch-error"]').exists()).toBe(true)
    const retry = wrapper.find('[data-testid="follow-retry-button"]')
    expect(retry.exists()).toBe(true)
    await retry.trigger('click')
    expect(wrapper.emitted('retryFollowStatus')).toHaveLength(1)
  })

  // AC-9
  it('AC-9: 同期失敗中は NONE でも「フォローする」を出さず、同期エラー＋再試行を出す', async () => {
    const wrapper = await mountHeader({
      roleName: null,
      followStatus: 'NONE',
      supporterEnabled: true,
      followPermissionSyncError: true,
    })
    expect(wrapper.find('[data-testid="follow-apply-button"]').exists()).toBe(false)
    const retry = wrapper.find('[data-testid="follow-permission-sync-retry-button"]')
    expect(wrapper.find('[data-testid="follow-permission-sync-error"]').exists()).toBe(true)
    expect(retry.exists()).toBe(true)
    await retry.trigger('click')
    expect(wrapper.emitted('retryFollowPermissionSync')).toHaveLength(1)
  })
})
