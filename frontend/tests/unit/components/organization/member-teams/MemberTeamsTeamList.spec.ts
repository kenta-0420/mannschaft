import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import type { OrgTeam } from '~/types/organization'
import MemberTeamsTeamList from '~/components/organization/member-teams/MemberTeamsTeamList.vue'
import { GROUP_A, GROUP_B, primeStubs } from './stubs'

/**
 * F01.2.1 8-C — 所属チーム一覧（member-teams 既存の一覧）のグループ列・絞り込み・割当の FE-UT。
 * 設計書 §13: 閲覧（グループ列・絞り込み）は MEMBER 以上、割当 UI は組織 ADMIN・管理レンズ ON のみ。
 *
 *   TL-01 グループ列と絞り込みが出る（割当 UI なし = MEMBER・DEPUTY・レンズ OFF の ADMIN 相当）
 *   TL-02 「未分類」「グループ」で絞り込める（取得し直さず、親が持つ一覧を絞る）
 *   TL-03 機能 off(409) は従来の一覧（グループ列・絞り込みなし）
 *   TL-04 グループ非公開の閲覧者(403)は従来の一覧。エラーにしない
 *   TL-05 グループ一覧の取得失敗はエラーを出し、従来の一覧は残す
 *   TL-06 canAssign のときだけ割当 UI（選択・単体・一括）が出る
 *   TL-07 単体割当は API を呼び changed を発火 / TL-08 未分類へ戻すと groupId=null
 *   TL-09 一括割当は選択したチームだけを送る / TL-10 未選択なら押せない
 *   TL-11 割当の失敗はエラー表示 / TL-12 429 は専用メッセージ
 *   TL-13 ORG_064 は changed を発火しグループ一覧も取り直す
 *   TL-14 絞り込みを変えたら選択をクリアし、見えていないチームを一括割当に含めない
 *   TL-15 組織が切り替わったら、古い組織の遅れた応答は捨てる
 *   TL-16 一括割当の成功で選択が空になる
 */

const listTeamGroupList = vi.fn()
const assignTeamGroup = vi.fn()
const bulkAssignTeamGroup = vi.fn()

vi.mock('~/composables/useOrgTeamMembershipApi', () => ({
  useOrgTeamMembershipApi: () => ({ listTeamGroupList, assignTeamGroup, bulkAssignTeamGroup }),
}))

function team(id: number, slug: string, groupId?: string, groupName?: string): OrgTeam {
  return {
    id,
    slug,
    name: `チーム${id}`,
    nickname1: null,
    iconUrl: null,
    template: 'GENERAL',
    memberCount: 3,
    teamGroup: groupId ? { id: groupId, name: groupName ?? '', sortOrder: 0 } : null,
  }
}

const TEAMS = [team(1, 'team-1', GROUP_A, '東地区'), team(2, 'team-2'), team(3, 'team-3')]
const GROUPS = [
  { id: GROUP_A, name: '東地区', sortOrder: 0, teamCount: 1 },
  { id: GROUP_B, name: '西地区', sortOrder: 1, teamCount: 0 },
]

function groupList(groups = GROUPS) {
  return { groups, unassignedTeamCount: 2, limit: 100 }
}

function apiError(status: number, code: string) {
  return { statusCode: status, data: { error: { code } } }
}

const stubs = { ...primeStubs, OrgTeamGrid: { template: '<div data-testid="stub-grid" />' } }

async function mountList(props: Record<string, unknown> = {}) {
  const wrapper = await mountSuspended(MemberTeamsTeamList, {
    props: { orgSlug: 'org-1', teams: TEAMS, canAssign: false, ...props },
    global: { stubs },
  })
  await flushPromises()
  return wrapper
}

function rowSlugs(wrapper: Awaited<ReturnType<typeof mountList>>): string[] {
  return wrapper.findAll('[data-testid="team-row"]').map(w => w.attributes('data-team-slug') ?? '')
}

describe('MemberTeamsTeamList', () => {
  beforeEach(() => {
    for (const fn of [listTeamGroupList, assignTeamGroup, bulkAssignTeamGroup]) fn.mockReset()
    listTeamGroupList.mockResolvedValue(groupList())
    assignTeamGroup.mockResolvedValue(undefined)
    bulkAssignTeamGroup.mockResolvedValue({ updatedCount: 2 })
  })

  it('TL-01: グループ列と絞り込みが出る。割当 UI は出ない', async () => {
    const wrapper = await mountList()
    expect(wrapper.find('[data-testid="teamlist-filter"]').exists()).toBe(true)
    const labels = wrapper.findAll('[data-testid="team-group-label"]').map(w => w.text())
    expect(labels).toHaveLength(3)
    expect(labels[0]).toBe('東地区')
    expect(labels[1]).not.toBe('東地区')
    expect(labels[2]).toBe(labels[1])
    expect(wrapper.find('[data-testid="team-assign-team-2"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="team-select-team-2"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="bulk-submit"]').exists()).toBe(false)
  })

  it('TL-02: 「未分類」「グループ」で絞り込める', async () => {
    const wrapper = await mountList()
    await wrapper.find('[data-testid="teamlist-filter"]').setValue('__unassigned__')
    expect(rowSlugs(wrapper)).toEqual(['team-2', 'team-3'])
    await wrapper.find('[data-testid="teamlist-filter"]').setValue(GROUP_A)
    expect(rowSlugs(wrapper)).toEqual(['team-1'])
    await wrapper.find('[data-testid="teamlist-filter"]').setValue(GROUP_B)
    expect(rowSlugs(wrapper)).toEqual([])
    expect(wrapper.find('[data-testid="teamlist-empty"]').exists()).toBe(true)
  })

  it('TL-03: 機能 off(一覧が null)は従来の一覧で、グループ列・絞り込みを出さない', async () => {
    listTeamGroupList.mockResolvedValue(null)
    const wrapper = await mountList({ canAssign: true })
    expect(wrapper.find('[data-testid="stub-grid"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="teamlist-filter"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="bulk-submit"]').exists()).toBe(false)
  })

  it('TL-04: グループを見られない閲覧者(403)は従来の一覧で、エラーを出さない', async () => {
    listTeamGroupList.mockRejectedValue(apiError(403, 'COMMON_002'))
    const wrapper = await mountList()
    expect(wrapper.find('[data-testid="stub-grid"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="teamlist-groups-error"]').exists()).toBe(false)
  })

  it('TL-05: グループ一覧の取得失敗はエラーを出し、従来の一覧は残す', async () => {
    listTeamGroupList.mockRejectedValue(apiError(500, 'COMMON_500'))
    const wrapper = await mountList()
    expect(wrapper.find('[data-testid="teamlist-groups-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="stub-grid"]').exists()).toBe(true)
  })

  it('TL-06: canAssign のときだけ割当 UI（選択・単体・一括）が出る', async () => {
    const wrapper = await mountList({ canAssign: true })
    expect(wrapper.find('[data-testid="team-assign-team-2"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="team-select-team-2"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="bulk-submit"]').exists()).toBe(true)
  })

  it('TL-07: 単体割当は API を呼び、changed を発火する', async () => {
    const wrapper = await mountList({ canAssign: true })
    await wrapper.find('[data-testid="team-assign-team-2"]').setValue(GROUP_B)
    await flushPromises()
    expect(assignTeamGroup).toHaveBeenCalledWith('org-1', 'team-2', GROUP_B)
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('TL-08: 「未分類」を選ぶと groupId=null で割り当てる', async () => {
    const wrapper = await mountList({ canAssign: true })
    await wrapper.find('[data-testid="team-assign-team-1"]').setValue('__unassigned__')
    await flushPromises()
    expect(assignTeamGroup).toHaveBeenCalledWith('org-1', 'team-1', null)
  })

  it('TL-09: 一括割当は選択したチームだけを送る', async () => {
    const wrapper = await mountList({ canAssign: true })
    await wrapper.find('[data-testid="team-select-team-2"]').setValue(true)
    await wrapper.find('[data-testid="team-select-team-3"]').setValue(true)
    await wrapper.find('[data-testid="bulk-target"]').setValue(GROUP_B)
    await wrapper.find('[data-testid="bulk-submit"]').trigger('click')
    await flushPromises()
    expect(bulkAssignTeamGroup).toHaveBeenCalledWith('org-1', GROUP_B, ['team-2', 'team-3'])
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('TL-10: チーム未選択なら一括割当は押せない', async () => {
    const wrapper = await mountList({ canAssign: true })
    expect(wrapper.find('[data-testid="bulk-submit"]').attributes('disabled')).toBeDefined()
  })

  it('TL-11: 割当の失敗はエラー表示になり、changed は発火しない', async () => {
    assignTeamGroup.mockRejectedValue(apiError(500, 'COMMON_500'))
    const wrapper = await mountList({ canAssign: true })
    await wrapper.find('[data-testid="team-assign-team-2"]').setValue(GROUP_B)
    await flushPromises()
    expect(wrapper.find('[data-testid="assign-error"]').exists()).toBe(true)
    expect(wrapper.emitted('changed')).toBeUndefined()
  })

  it('TL-12: 一括割当の 429 は専用メッセージを出す', async () => {
    bulkAssignTeamGroup.mockRejectedValue({ statusCode: 429, data: {} })
    const wrapper = await mountList({ canAssign: true })
    await wrapper.find('[data-testid="team-select-team-2"]').setValue(true)
    await wrapper.find('[data-testid="bulk-target"]').setValue(GROUP_B)
    await wrapper.find('[data-testid="bulk-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="assign-error"]').attributes('data-error-kind')).toBe('rate-limited')
  })

  it('TL-13: ORG_064（グループが消えていた）は changed を発火し、グループ一覧も取り直す', async () => {
    assignTeamGroup.mockRejectedValue(apiError(404, 'ORG_064'))
    const wrapper = await mountList({ canAssign: true })
    await wrapper.find('[data-testid="team-assign-team-2"]').setValue(GROUP_B)
    await flushPromises()
    expect(wrapper.find('[data-testid="assign-error"]').attributes('data-error-kind')).toBe('group-missing')
    expect(wrapper.emitted('changed')).toHaveLength(1)
    expect(listTeamGroupList).toHaveBeenCalledTimes(2)
  })

  it('TL-14: 絞り込みを変えたら選択をクリアし、見えていないチームを一括割当に含めない', async () => {
    const wrapper = await mountList({ canAssign: true })
    await wrapper.find('[data-testid="team-select-team-2"]').setValue(true)
    expect(wrapper.find('[data-testid="bulk-selected-count"]').text()).toContain('1')
    // team-2 は「東地区」の絞り込みでは見えない
    await wrapper.find('[data-testid="teamlist-filter"]').setValue(GROUP_A)
    expect(wrapper.find('[data-testid="bulk-selected-count"]').text()).toContain('0')
    expect(wrapper.find('[data-testid="bulk-submit"]').attributes('disabled')).toBeDefined()
    // 見えているチーム(team-1)だけを選んで送る
    await wrapper.find('[data-testid="team-select-team-1"]').setValue(true)
    await wrapper.find('[data-testid="bulk-target"]').setValue(GROUP_B)
    await wrapper.find('[data-testid="bulk-submit"]').trigger('click')
    await flushPromises()
    expect(bulkAssignTeamGroup).toHaveBeenCalledWith('org-1', GROUP_B, ['team-1'])
  })

  it('TL-15: 組織が切り替わったら、古い組織の遅れた応答は捨てる', async () => {
    let resolveOld: (v: unknown) => void = () => {}
    listTeamGroupList.mockReset()
    listTeamGroupList
      .mockReturnValueOnce(new Promise((r) => { resolveOld = r }))
      .mockResolvedValueOnce(groupList([{ id: GROUP_B, name: '新組織のグループ', sortOrder: 0, teamCount: 0 }]))
    const wrapper = await mountSuspended(MemberTeamsTeamList, {
      props: { orgSlug: 'org-old', teams: TEAMS, canAssign: false },
      global: { stubs },
    })
    await wrapper.setProps({ orgSlug: 'org-new' })
    await flushPromises()
    resolveOld(groupList([{ id: GROUP_A, name: '旧組織のグループ', sortOrder: 0, teamCount: 0 }]))
    await flushPromises()
    const options = wrapper.find('[data-testid="teamlist-filter"]').text()
    expect(options).toContain('新組織のグループ')
    expect(options).not.toContain('旧組織のグループ')
  })

  it('TL-17: 親が一覧を再取得している間は、割当操作（単体・選択・一括）を受け付けない', async () => {
    const wrapper = await mountList({ canAssign: true, refreshing: true })
    expect(wrapper.find('[data-testid="team-assign-team-2"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="team-select-team-2"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="bulk-submit"]').attributes('disabled')).toBeDefined()
    await wrapper.setProps({ refreshing: false })
    expect(wrapper.find('[data-testid="team-assign-team-2"]').attributes('disabled')).toBeUndefined()
  })

  it('TL-18: 一覧の取得失敗は「該当チームなし」に見せず、エラーと再試行を出す。選択は消さない', async () => {
    const wrapper = await mountList({ canAssign: true })
    await wrapper.find('[data-testid="team-select-team-2"]').setValue(true)
    await wrapper.setProps({ loadFailed: true })
    expect(wrapper.find('[data-testid="teamlist-load-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="teamlist-empty"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-testid="team-row"]')).toHaveLength(0)
    await wrapper.find('[data-testid="teamlist-retry"]').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
    // 回復したら選択は残っている
    await wrapper.setProps({ loadFailed: false })
    expect(wrapper.find('[data-testid="bulk-selected-count"]').text()).toContain('1')
  })

  it('TL-16: 一括割当に成功したら選択が空になる', async () => {
    const wrapper = await mountList({ canAssign: true })
    await wrapper.find('[data-testid="team-select-team-2"]').setValue(true)
    await wrapper.find('[data-testid="bulk-target"]').setValue(GROUP_B)
    await wrapper.find('[data-testid="bulk-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="bulk-selected-count"]').text()).toContain('0')
  })
})
