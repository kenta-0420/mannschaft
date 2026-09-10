import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { TeamResponse } from '~/types/team'
import type { JoinRequestUiStatus } from '~/composables/useJoinRequestApi'
import TeamPageHeader from './TeamPageHeader.vue'

/**
 * 参加申請ボタンの状態別描画・無効化のテスト（Codex 検分 CMP-260901-1538 第1巡 P1-1/P1-5 是正）。
 *
 * 是正前は取得失敗が `NONE`（未申請）へ潰され、申請ボタンが常に有効だった（fail-open）。
 * `UNKNOWN`・`LOADING`・`ERROR` では申請操作を無効化する（fail-close）ことをここで固定する。
 */

const stubs = {
  ProfileHeader: { template: '<div><slot /></div>' },
  FavoriteToggleButton: true,
  RoleBadge: true,
  Tag: true,
  Menu: true,
  BroadcastWizard: true,
  Button: {
    props: ['label', 'loading', 'disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

function makeTeam(): TeamResponse {
  return {
    id: 1,
    numericId: 1,
    slug: 'team-a',
    visibility: { visibility: 'PUBLIC', supporterEnabled: false },
    metadata: { memberCount: 3 },
    location: { template: 'default' },
  } as unknown as TeamResponse
}

async function mountHeader(joinRequestStatus: JoinRequestUiStatus) {
  return mountSuspended(TeamPageHeader, {
    props: {
      team: makeTeam(),
      displayName: 'チームA',
      roleName: null,
      isAdmin: false,
      isAdminOrDeputy: false,
      followStatus: 'NONE',
      followLoading: false,
      joinRequestStatus,
      joinRequestLoading: false,
      templateLabel: {},
    },
    global: { stubs },
  })
}

describe('TeamPageHeader 参加申請', () => {
  // Codex 検分第3巡 P2 是正: REJECTED でも申請ボタンは有効（契約と矛盾しないテスト名にする）
  it('NONE のとき申請ボタンが有効になる', async () => {
    const wrapper = await mountHeader('NONE')
    const button = wrapper.find('[data-testid="join-request-apply-button"]')
    expect(button.exists()).toBe(true)
    expect(button.attributes('disabled')).toBeUndefined()
  })

  it.each(['UNKNOWN', 'LOADING'] as const)('%s のときは申請ボタンが無効化される（fail-close）', async (status) => {
    const wrapper = await mountHeader(status)
    const button = wrapper.find('[data-testid="join-request-apply-button"]')
    expect(button.exists()).toBe(true)
    expect(button.attributes('disabled')).toBeDefined()
  })

  it('ERROR のときはエラー表示になり、申請ボタンではなく再試行ボタンが出る', async () => {
    const wrapper = await mountHeader('ERROR')
    expect(wrapper.find('[data-testid="join-request-apply-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="join-request-error"]').exists()).toBe(true)
    const retryButton = wrapper.find('[data-testid="join-request-retry-button"]')
    expect(retryButton.exists()).toBe(true)

    await retryButton.trigger('click')
    expect(wrapper.emitted('retryJoinRequestStatus')).toHaveLength(1)
  })

  it('PENDING のときは申請中表示になり、申請ボタンは出ない', async () => {
    const wrapper = await mountHeader('PENDING')
    expect(wrapper.find('[data-testid="join-request-pending"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="join-request-apply-button"]').exists()).toBe(false)
  })

  it('APPROVED のときは承認済み表示になる', async () => {
    const wrapper = await mountHeader('APPROVED')
    expect(wrapper.find('[data-testid="join-request-approved"]').exists()).toBe(true)
  })

  // Codex 検分第2巡 P1-1 是正: BE は却下後の新規申請を許可しているため、
  // REJECTED でも申請ボタンは有効なままでなければならない（fail-open ではなく
  // 正規の再申請経路を塞いだ前巡の退行）。
  it('REJECTED のときは却下表示を出しつつ、申請ボタンは有効なまま（再申請できる）', async () => {
    const wrapper = await mountHeader('REJECTED')
    expect(wrapper.find('[data-testid="join-request-rejected"]').exists()).toBe(true)
    const button = wrapper.find('[data-testid="join-request-apply-button"]')
    expect(button.exists()).toBe(true)
    expect(button.attributes('disabled')).toBeUndefined()

    await button.trigger('click')
    expect(wrapper.emitted('applyJoinRequest')).toHaveLength(1)
  })

  it('NONE の申請ボタンをクリックすると applyJoinRequest が発火する', async () => {
    const wrapper = await mountHeader('NONE')
    await wrapper.find('[data-testid="join-request-apply-button"]').trigger('click')
    expect(wrapper.emitted('applyJoinRequest')).toHaveLength(1)
  })
})
