import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { OrgDetail } from '~/composables/useOrgDetail'
import type { FollowUiStatus } from '~/composables/useFollowSelfStatus'
import OrgPageHeader from './OrgPageHeader.vue'

/**
 * フォロー解除・退出表示の受け入れ条件テスト（組織ヘッダー・CMP-261001-0835）。
 *
 * 是正前は `org.visibility?.supporterEnabled && !roleName` でフォロー系ブロック全体を
 * ガードしていたため、フォロー承認で付与される SUPPORTER ロール自身には
 * 「フォロー解除」が一度も出なかった（AC-1）。また `!isAdmin && roleName` だけで退出を
 * 出していたため、SUPPORTER にも退出が出てしまっていた（AC-2）。
 */

const MenuStub = {
  props: ['model'],
  template: '<div data-testid="overflow-menu-stub" />',
}

const stubs = {
  ProfileHeader: { template: '<div><slot /></div>' },
  FavoriteToggleButton: true,
  RoleBadge: true,
  Menu: MenuStub,
  BroadcastWizard: true,
  Button: {
    props: ['label', 'loading', 'disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

function makeOrg(overrides: Partial<OrgDetail['visibility']> = {}): OrgDetail {
  return {
    id: 'org-a',
    numericId: 1,
    basicInfo: { name: '組織A' },
    visibility: { visibility: 'PRIVATE', supporterEnabled: false, ...overrides },
    metadata: { memberCount: 3 },
  } as unknown as OrgDetail
}

interface MountOpts {
  roleName: string | null
  isAdmin?: boolean
  followStatus: FollowUiStatus
  followPermissionSyncError?: boolean
  supporterEnabled?: boolean
}

async function mountHeader(opts: MountOpts) {
  return mountSuspended(OrgPageHeader, {
    props: {
      org: makeOrg({ supporterEnabled: opts.supporterEnabled ?? false }),
      orgId: 'org-a',
      roleName: opts.roleName,
      isAdmin: opts.isAdmin ?? false,
      isAdminOrDeputy: opts.isAdmin ?? false,
      followStatus: opts.followStatus,
      followLoading: false,
      followPermissionSyncError: opts.followPermissionSyncError ?? false,
      joinRequestStatus: 'UNKNOWN',
      joinRequestLoading: false,
      ancestors: [],
    },
    global: { stubs },
  })
}

describe('OrgPageHeader フォロー解除・退出（CMP-261001-0835）', () => {
  // AC-1: APPROVED ならロール表示に関係なく「フォロー解除」が出る。supporterEnabled=false でも出る。
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

  // AC-2: 応援者(SUPPORTER)には「組織から退出」がインラインにも⋯メニューにも出ない。
  it('AC-2: SUPPORTER には退出ボタン（インライン）が出ない', async () => {
    const wrapper = await mountHeader({ roleName: 'SUPPORTER', isAdmin: false, followStatus: 'APPROVED' })
    expect(wrapper.find('[data-testid="org-leave-button"]').exists()).toBe(false)
  })

  it('AC-2: SUPPORTER には⋯メニューにも退出項目が無い', async () => {
    const wrapper = await mountHeader({ roleName: 'SUPPORTER', isAdmin: false, followStatus: 'APPROVED' })
    const menu = wrapper.findComponent(MenuStub)
    const items = (menu.props('model') ?? []) as { label: string }[]
    expect(items.some(i => i.label === '組織から退出')).toBe(false)
  })

  // AC-3: MEMBER(非ADMIN)は従来どおり退出が出てフォロー系は出ない。ADMIN は退出なし。
  it('AC-3: MEMBER(非ADMIN) には退出が出る', async () => {
    const wrapper = await mountHeader({ roleName: 'MEMBER', isAdmin: false, followStatus: 'NONE' })
    expect(wrapper.find('[data-testid="org-leave-button"]').exists()).toBe(true)
  })

  it('AC-3: MEMBER にはフォロー系ボタンが出ない', async () => {
    const wrapper = await mountHeader({ roleName: 'MEMBER', isAdmin: false, followStatus: 'NONE', supporterEnabled: true })
    expect(wrapper.find('[data-testid="follow-apply-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="follow-unfollow-button"]').exists()).toBe(false)
  })

  it('AC-3: ADMIN には退出が出ない', async () => {
    const wrapper = await mountHeader({ roleName: 'ADMIN', isAdmin: true, followStatus: 'NONE' })
    expect(wrapper.find('[data-testid="org-leave-button"]').exists()).toBe(false)
  })

  // AC-4: NONE の「フォローする」は supporterEnabled=true のときだけ。
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
  it('AC-4 不足分: org.visibility が未設定でも「フォローする」は出ない（例外も発生しない）', async () => {
    const wrapper = await mountSuspended(OrgPageHeader, {
      props: {
        org: { id: 'org-a', numericId: 1, basicInfo: { name: '組織A' }, metadata: { memberCount: 3 } } as unknown as OrgDetail,
        orgId: 'org-a',
        roleName: null,
        isAdmin: false,
        isAdminOrDeputy: false,
        followStatus: 'NONE',
        followLoading: false,
        followPermissionSyncError: false,
        joinRequestStatus: 'UNKNOWN',
        joinRequestLoading: false,
        ancestors: [],
      },
      global: { stubs },
    })
    expect(wrapper.find('[data-testid="follow-apply-button"]').exists()).toBe(false)
  })

  // AC-6: 未取得・取得失敗の間は「フォローする」を押せない。失敗時はエラーと再試行手段。
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

  // AC-9: 解除成功・権限再取得失敗時は「フォローする」を出さず、同期エラー＋再試行を出す。
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
