import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import TeamInviteDialog from '~/components/organization/member-teams/TeamInviteDialog.vue'
import { GROUP_A, primeStubs } from './stubs'

/**
 * F01.2.1 8-B — チーム招待ダイアログ（公開チーム検索）の FE-UT。
 *
 * 検証観点（AC-C01〜C03 のうち招待の UI 側）:
 *   IV-01 キーワードで公開チームを検索し、結果を出す
 *   IV-02 キーワードが空なら検索できない
 *   IV-03 チームを選ばなければ招待できない
 *   IV-04 選んだチームと添え書きが送信値に入る
 *   IV-05 グループ機能 on なら任意でグループを選べ、groupId が入る
 *   IV-06 検索結果が 0 件なら「見つかりません」を出す
 *   IV-07 検索失敗はエラー表示する
 *   IV-08 招待成功で invited を発火して閉じる
 *   IV-09 招待失敗はエラー表示で閉じない
 */

const inviteTeam = vi.fn()
const listTeamGroups = vi.fn()
const searchPublicTeams = vi.fn()

vi.mock('~/composables/useOrgTeamMembershipApi', () => ({
  useOrgTeamMembershipApi: () => ({ inviteTeam, listTeamGroups }),
}))
vi.mock('~/composables/usePublicApi', () => ({
  usePublicApi: () => ({ searchPublicTeams }),
}))

function pageOf(...teams: { slug: string, name: string }[]) {
  return {
    content: teams.map((t, i) => ({ id: i + 1, iconUrl: null, memberCount: 10, lastPostDate: null, ...t })),
    totalElements: teams.length,
    totalPages: 1,
    number: 0,
    size: 20,
    first: true,
    last: true,
    empty: teams.length === 0,
    numberOfElements: teams.length,
  }
}

async function mountDialog() {
  const wrapper = await mountSuspended(TeamInviteDialog, {
    props: { visible: true, orgSlug: 'org-1' },
    global: { stubs: primeStubs },
  })
  await flushPromises()
  return wrapper
}

type Wrapper = Awaited<ReturnType<typeof mountDialog>>

async function search(w: Wrapper, keyword = 'タイガース') {
  await w.find('[data-testid="invite-search-input"]').setValue(keyword)
  await w.find('[data-testid="invite-search-submit"]').trigger('click')
  await flushPromises()
}

const submit = (w: Wrapper) => w.find('[data-testid="invite-submit"]')

describe('TeamInviteDialog', () => {
  beforeEach(() => {
    inviteTeam.mockReset()
    inviteTeam.mockResolvedValue(undefined)
    listTeamGroups.mockReset()
    listTeamGroups.mockResolvedValue(null)
    searchPublicTeams.mockReset()
    searchPublicTeams.mockResolvedValue(pageOf({ slug: 'team-x', name: 'タイガース' }))
  })

  it('IV-01: キーワードで公開チームを検索し、結果を出す', async () => {
    const wrapper = await mountDialog()
    await search(wrapper)
    expect(searchPublicTeams).toHaveBeenCalledWith(expect.objectContaining({ keyword: 'タイガース' }))
    expect(wrapper.find('[data-testid="invite-result-team-x"]').exists()).toBe(true)
  })

  it('IV-02: キーワードが空なら検索できない', async () => {
    const wrapper = await mountDialog()
    expect(wrapper.find('[data-testid="invite-search-submit"]').attributes('disabled')).toBeDefined()
    await wrapper.find('[data-testid="invite-search-submit"]').trigger('click')
    expect(searchPublicTeams).not.toHaveBeenCalled()
  })

  it('IV-03: チームを選ばなければ招待できない', async () => {
    const wrapper = await mountDialog()
    expect(submit(wrapper).attributes('disabled')).toBeDefined()
    await search(wrapper)
    expect(submit(wrapper).attributes('disabled')).toBeDefined()
    await submit(wrapper).trigger('click')
    expect(inviteTeam).not.toHaveBeenCalled()
  })

  it('IV-04: 選んだチームと添え書きが送信値に入る', async () => {
    const wrapper = await mountDialog()
    await search(wrapper)
    await wrapper.find('[data-testid="invite-result-team-x"]').trigger('click')
    await wrapper.find('[data-testid="invite-message"]').setValue('ぜひ加盟してください')
    expect(submit(wrapper).attributes('disabled')).toBeUndefined()
    await submit(wrapper).trigger('click')
    await flushPromises()
    expect(inviteTeam).toHaveBeenCalledWith('org-1', {
      teamSlug: 'team-x',
      message: 'ぜひ加盟してください',
      groupId: undefined,
    })
  })

  it('IV-05: グループ機能 on なら任意でグループを選べ、groupId が入る', async () => {
    listTeamGroups.mockResolvedValue([{ id: GROUP_A, name: '平成20年度', teamCount: 1 }])
    const wrapper = await mountDialog()
    await search(wrapper)
    await wrapper.find('[data-testid="invite-result-team-x"]').trigger('click')
    await wrapper.find('[data-testid="invite-group-select"]').setValue(GROUP_A)
    await submit(wrapper).trigger('click')
    await flushPromises()
    expect(inviteTeam.mock.calls[0]![1].groupId).toBe(GROUP_A)
  })

  it('IV-06: 検索結果が 0 件なら「見つかりません」を出す', async () => {
    searchPublicTeams.mockResolvedValue(pageOf())
    const wrapper = await mountDialog()
    await search(wrapper)
    expect(wrapper.find('[data-testid="invite-search-empty"]').exists()).toBe(true)
  })

  it('IV-07: 検索失敗はエラー表示する', async () => {
    searchPublicTeams.mockRejectedValue(new Error('network'))
    const wrapper = await mountDialog()
    await search(wrapper)
    expect(wrapper.find('[data-testid="invite-search-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="invite-search-empty"]').exists()).toBe(false)
  })

  it('IV-08: 招待成功で invited を発火し、visible を false にする', async () => {
    const wrapper = await mountDialog()
    await search(wrapper)
    await wrapper.find('[data-testid="invite-result-team-x"]').trigger('click')
    await submit(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.emitted('invited')).toHaveLength(1)
    expect(wrapper.emitted('update:visible')?.at(-1)).toEqual([false])
  })

  it('IV-09: 招待失敗はエラー表示になり、閉じず、invited も発火しない', async () => {
    inviteTeam.mockRejectedValue(new Error('TEAM_068'))
    const wrapper = await mountDialog()
    await search(wrapper)
    await wrapper.find('[data-testid="invite-result-team-x"]').trigger('click')
    await submit(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="invite-error"]').exists()).toBe(true)
    expect(wrapper.emitted('invited')).toBeUndefined()
    expect(wrapper.emitted('update:visible')).toBeUndefined()
  })
})
