import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import type { ConfirmationOptions } from 'primevue/confirmationoptions'
import type { OrgTeam } from '~/types/organization'
import MemberTeamsTeamList from './MemberTeamsTeamList.vue'
import { primeStubs } from '../../../../tests/unit/components/organization/member-teams/stubs'

const mocks = vi.hoisted(() => ({
  removeTeam: vi.fn(),
  listTeamGroupList: vi.fn(),
  require: vi.fn(),
  close: vi.fn(),
  success: vi.fn(),
  handleApiError: vi.fn(),
}))
mockNuxtImport('useConfirm', () => () => ({ require: mocks.require, close: mocks.close }))
mockNuxtImport('useNotification', () => () => ({ success: mocks.success }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))
vi.mock('~/composables/useOrgTeamMembershipApi', () => ({
  useOrgTeamMembershipApi: () => ({
    listTeamGroupList: mocks.listTeamGroupList,
    removeTeam: mocks.removeTeam,
  }),
}))

const TEAM: OrgTeam = {
  id: 1,
  slug: 'team-1',
  name: '対象チーム',
  nickname1: null,
  iconUrl: null,
  template: 'GENERAL',
  memberCount: 3,
  teamGroup: null,
}

async function mountList(props: Record<string, unknown> = {}) {
  const wrapper = await mountSuspended(MemberTeamsTeamList, {
    props: { orgSlug: 'org-1', teams: [TEAM], canAssign: true, ...props },
    global: { stubs: primeStubs },
  })
  await flushPromises()
  return wrapper
}

function confirmation(): ConfirmationOptions {
  return mocks.require.mock.calls.at(-1)?.[0] as ConfirmationOptions
}

async function accept() {
  await confirmation().accept?.()
  await flushPromises()
}

describe('加盟チームの除名（AC-E02/E03/P08 の FE 契約）', () => {
  beforeEach(() => {
    Object.values(mocks).forEach((fn) => fn.mockReset())
    mocks.listTeamGroupList.mockResolvedValue({ groups: [], unassignedTeamCount: 1, limit: 100 })
    mocks.removeTeam.mockResolvedValue(undefined)
  })

  it('入口から共通確認を開き、承認前・キャンセル時は API を送らない', async () => {
    const wrapper = await mountList()
    expect(wrapper.find('[data-testid="team-remove-team-1"]').exists()).toBe(true)
    await wrapper.find('[data-testid="team-remove-team-1"]').trigger('click')
    expect(mocks.close).toHaveBeenCalledTimes(1)
    expect(mocks.close.mock.invocationCallOrder[0]).toBeLessThan(mocks.require.mock.invocationCallOrder[0]!)
    expect(confirmation().group).toBe('team-affiliation-remove')
    expect(confirmation().message).toContain(TEAM.name)
    expect(confirmation().rejectLabel).toBeTruthy()
    expect(mocks.removeTeam).not.toHaveBeenCalled()
    confirmation().reject?.()
    expect(mocks.removeTeam).not.toHaveBeenCalled()
  })

  it('204 成功で一覧の再取得を要求し、成功を通知する（ローカルで行を消さない）', async () => {
    const wrapper = await mountList()
    await wrapper.find('[data-testid="team-remove-team-1"]').trigger('click')
    await accept()
    expect(mocks.removeTeam).toHaveBeenCalledWith('org-1', 'team-1')
    expect(wrapper.emitted('changed')).toHaveLength(1)
    expect(mocks.success).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="team-row"]').exists()).toBe(true)
  })

  it.each([403, 404, 500])(
    '%s 失敗は行を保持し、明示エラーの後に再確認して再試行できる',
    async (status) => {
      mocks.removeTeam.mockRejectedValueOnce({ statusCode: status })
      const wrapper = await mountList()
      await wrapper.find('[data-testid="team-remove-team-1"]').trigger('click')
      await accept()
      expect(wrapper.find('[data-testid="team-remove-error"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="team-row"]').exists()).toBe(true)
      expect(wrapper.emitted('changed')).toBeUndefined()
      expect(mocks.success).not.toHaveBeenCalled()
      expect(mocks.handleApiError).toHaveBeenCalledTimes(1)
      if (status === 404) {
        await wrapper.find('[data-testid="team-remove-reload"]').trigger('click')
        expect(wrapper.emitted('retry')).toHaveLength(1)
        expect(mocks.removeTeam).toHaveBeenCalledTimes(1)
      }
      await wrapper.find('[data-testid="team-remove-team-1"]').trigger('click')
      await accept()
      expect(mocks.removeTeam).toHaveBeenCalledTimes(2)
      expect(wrapper.emitted('changed')).toHaveLength(1)
    },
  )

  it('グループ機能 off の実カードにも除名があり、カード遷移を起こさず再取得を要求する', async () => {
    mocks.listTeamGroupList.mockResolvedValue(null)
    const wrapper = await mountList()
    expect(wrapper.find('[data-testid="member-teams-team-list"]').exists()).toBe(false)
    await wrapper.find('[data-testid="team-remove-team-1"]').trigger('click')
    await accept()
    expect(mocks.removeTeam).toHaveBeenCalledWith('org-1', 'team-1')
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it.each([false, true])(
    'グループ off=%s でも権限のない canAssign=false には除名を出さない',
    async (off) => {
      if (off) mocks.listTeamGroupList.mockResolvedValue(null)
      const wrapper = await mountList({ canAssign: false })
      expect(wrapper.find('[data-testid="team-remove-team-1"]').exists()).toBe(false)
    },
  )

  it('送信中の再承認は二重送信しない', async () => {
    let resolve: () => void = () => {}
    mocks.removeTeam.mockReturnValue(
      new Promise<void>((r) => {
        resolve = r
      }),
    )
    const wrapper = await mountList()
    await wrapper.find('[data-testid="team-remove-team-1"]').trigger('click')
    const first = confirmation().accept?.()
    await flushPromises()
    expect(wrapper.find('[data-testid="team-remove-team-1"]').attributes('disabled')).toBeDefined()
    await confirmation().accept?.()
    expect(mocks.removeTeam).toHaveBeenCalledTimes(1)
    resolve()
    await first
  })

  it('送信後に親が一覧を再取得しても、成功後にもう一度最新一覧を要求する', async () => {
    let resolve: () => void = () => {}
    mocks.removeTeam.mockReturnValue(
      new Promise<void>((r) => {
        resolve = r
      }),
    )
    const wrapper = await mountList()
    await wrapper.find('[data-testid="team-remove-team-1"]').trigger('click')
    const request = confirmation().accept?.()
    await wrapper.setProps({ refreshing: true })
    resolve()
    await request
    await flushPromises()
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it.each([
    { orgSlug: 'org-2' },
    { refreshing: true },
    { loadFailed: true },
    { canAssign: false },
    { teams: [{ ...TEAM, name: '最新のチーム' }] },
  ])('確認後にスコープ・一覧・権限が変わったら古い確認から送信しない: %j', async (props) => {
    const wrapper = await mountList()
    await wrapper.find('[data-testid="team-remove-team-1"]').trigger('click')
    const old = confirmation()
    await wrapper.setProps(props)
    await flushPromises()
    await old.accept?.()
    expect(mocks.removeTeam).not.toHaveBeenCalled()
  })

  it('グループ絞り込みで行が消えた後は古い確認から送信しない', async () => {
    mocks.listTeamGroupList.mockResolvedValue({
      groups: [{ id: 'other-group', name: '別グループ', sortOrder: 0, teamCount: 0 }],
      unassignedTeamCount: 1,
      limit: 100,
    })
    const wrapper = await mountList()
    await wrapper.find('[data-testid="team-remove-team-1"]').trigger('click')
    const old = confirmation()
    await wrapper.find('[data-testid="teamlist-filter"]').setValue('other-group')
    await old.accept?.()
    expect(mocks.removeTeam).not.toHaveBeenCalled()
  })

  it('送信後に絞り込みでボタンが消えても204後に正本一覧を同期し、一覧へ戻ってもチームが復活しない', async () => {
    mocks.listTeamGroupList.mockResolvedValue({
      groups: [{ id: 'other-group', name: '別グループ', sortOrder: 0, teamCount: 0 }],
      unassignedTeamCount: 1,
      limit: 100,
    })
    let resolve: () => void = () => {}
    mocks.removeTeam.mockReturnValue(
      new Promise<void>((r) => {
        resolve = r
      }),
    )
    const refresh = vi.fn(async () => {
      await wrapper.setProps({ teams: [] })
    })
    const wrapper = await mountList({ refreshAfterRemoval: refresh })
    await wrapper.find('[data-testid="team-remove-team-1"]').trigger('click')
    const request = confirmation().accept?.()
    await wrapper.find('[data-testid="teamlist-filter"]').setValue('other-group')
    expect(wrapper.find('[data-testid="team-remove-team-1"]').exists()).toBe(false)
    resolve()
    await request
    expect(refresh).toHaveBeenCalledWith('org-1')
    await wrapper.find('[data-testid="teamlist-filter"]').setValue('__all__')
    expect(wrapper.find('[data-testid="team-row"]').exists()).toBe(false)
  })

  it('送信後に別viewへ移り一覧を破棄しても204後の同期callbackは呼ばれる（古い画面の通知は出さない）', async () => {
    let resolve: () => void = () => {}
    mocks.removeTeam.mockReturnValue(
      new Promise<void>((r) => {
        resolve = r
      }),
    )
    const refresh = vi.fn().mockResolvedValue(undefined)
    const wrapper = await mountList({ refreshAfterRemoval: refresh })
    await wrapper.find('[data-testid="team-remove-team-1"]').trigger('click')
    const request = confirmation().accept?.()
    wrapper.unmount()
    resolve()
    await request
    expect(refresh).toHaveBeenCalledWith('org-1')
    expect(mocks.success).not.toHaveBeenCalled()
  })
})
