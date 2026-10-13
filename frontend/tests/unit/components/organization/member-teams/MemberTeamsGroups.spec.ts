import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import type { OrgTeamGroup } from '~/composables/useOrgTeamMembershipApi'
import MemberTeamsGroupsView from '~/components/organization/member-teams/MemberTeamsGroupsView.vue'
import MemberTeamsGroupList from '~/components/organization/member-teams/MemberTeamsGroupList.vue'
import MemberTeamsGroupAssignments from '~/components/organization/member-teams/MemberTeamsGroupAssignments.vue'
import { GROUP_A, GROUP_B, primeStubs } from './stubs'

/**
 * F01.2.1 8-C — チームグループ管理 view（member-teams?view=groups）の FE-UT。
 *
 * 検証観点（設計書 AC-L04 / AC-G01 / AC-F08 / AC-G143）:
 *   GV-01 グループ一覧と未分類件数が出る                 GV-02 機能 off（一覧が null）は誘導カードと設定への導線
 *   GV-03 取得失敗はエラー表示（機能 off や空に見せない）   GV-04 機能 off のときは管理 UI を出さない
 *   GL-01 作成: 名前を送って changed 発火                 GL-02 名前が空なら送らない
 *   GL-03 同名(ORG_065)は専用メッセージ                   GL-04 上限(ORG_066)は専用メッセージ。上限到達で作成ボタン無効
 *   GL-05 改名: 変更を送って changed 発火                 GL-06 削除は所属件数つきの確認を挟み、確定で削除
 *   GL-07 並び替え(↑↓)は全 ID を新しい順で送る            GL-08 先頭の↑・末尾の↓は無効
 *   GL-09 二重送信しない                                   GL-10 機能 off(ORG_067)になったら changed を発火
 *   GA-01 加盟チームにグループ列（未分類を含む）が出る     GA-02 「未分類」で絞ると unassigned=true で取得
 *   GA-03 グループで絞ると teamGroupId で取得              GA-04 単体割当で API を呼んで再取得し changed 発火
 *   GA-05 未分類へ戻す(groupId=null)                      GA-06 一括割当は選択したチームだけを送る
 *   GA-07 未選択なら一括割当は押せない                     GA-08 割当失敗はエラー表示（成功に見せない）
 *   GA-09 一括割当の 429 は専用メッセージ                  GA-10 取得失敗はエラー表示 / 0 件は空表示
 *   GA-11 ORG_064（グループが消えていた）は changed を発火して親に再取得させる
 */

const listTeamGroupList = vi.fn()
const createTeamGroup = vi.fn()
const updateTeamGroup = vi.fn()
const deleteTeamGroup = vi.fn()
const reorderTeamGroups = vi.fn()
const listGroupTeams = vi.fn()
const assignTeamGroup = vi.fn()
const bulkAssignTeamGroup = vi.fn()

vi.mock('~/composables/useOrgTeamMembershipApi', () => ({
  useOrgTeamMembershipApi: () => ({
    listTeamGroupList,
    createTeamGroup,
    updateTeamGroup,
    deleteTeamGroup,
    reorderTeamGroups,
    listGroupTeams,
    assignTeamGroup,
    bulkAssignTeamGroup,
  }),
}))

function group(id: string, name: string, sortOrder: number, teamCount = 0): OrgTeamGroup {
  return { id, name, sortOrder, teamCount }
}

const GROUPS = [group(GROUP_A, '東地区', 0, 3), group(GROUP_B, '西地区', 1, 0)]

function groupList(groups = GROUPS, unassignedTeamCount = 2, limit = 100) {
  return { groups, unassignedTeamCount, limit }
}

function apiError(status: number, code: string) {
  return { statusCode: status, data: { error: { code } } }
}

const stubs = { ...primeStubs }

describe('MemberTeamsGroupsView', () => {
  beforeEach(() => {
    listTeamGroupList.mockReset()
    listGroupTeams.mockReset()
    listTeamGroupList.mockResolvedValue(groupList())
    listGroupTeams.mockResolvedValue([])
  })

  async function mountView() {
    const wrapper = await mountSuspended(MemberTeamsGroupsView, {
      props: { orgSlug: 'org-1' },
      global: { stubs },
    })
    await flushPromises()
    return wrapper
  }

  it('GV-01: グループ一覧と未分類件数が出る', async () => {
    const wrapper = await mountView()
    expect(wrapper.findAll('[data-testid="group-row"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('東地区')
    expect(wrapper.find('[data-testid="groups-unassigned-count"]').text()).toContain('2')
  })

  it('GV-02: 機能 off（一覧が null）は誘導カードと設定への導線を出す', async () => {
    listTeamGroupList.mockResolvedValue(null)
    const wrapper = await mountView()
    expect(wrapper.find('[data-testid="groups-disabled-card"]').exists()).toBe(true)
    const link = wrapper.find('[data-testid="groups-disabled-link"]')
    expect(link.attributes('href')).toBe('/organizations/org-1/settings/team-affiliation')
  })

  it('GV-03: 取得失敗はエラー表示になり、機能 off の誘導にも空にもならない', async () => {
    listTeamGroupList.mockRejectedValue(apiError(500, 'COMMON_500'))
    const wrapper = await mountView()
    expect(wrapper.find('[data-testid="groups-load-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="groups-disabled-card"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-testid="group-row"]')).toHaveLength(0)
  })

  it('GV-04: 機能 off のときは管理 UI（作成・割当）を出さない', async () => {
    listTeamGroupList.mockResolvedValue(null)
    const wrapper = await mountView()
    expect(wrapper.find('[data-testid="group-create-open"]').exists()).toBe(false)
    expect(listGroupTeams).not.toHaveBeenCalled()
  })
})

describe('MemberTeamsGroupList', () => {
  beforeEach(() => {
    for (const fn of [createTeamGroup, updateTeamGroup, deleteTeamGroup, reorderTeamGroups]) fn.mockReset()
    createTeamGroup.mockResolvedValue(group('new', '新規', 2))
    updateTeamGroup.mockResolvedValue(group(GROUP_A, '改名後', 0))
    deleteTeamGroup.mockResolvedValue(undefined)
    reorderTeamGroups.mockResolvedValue([])
  })

  async function mountList(props: Record<string, unknown> = {}) {
    const wrapper = await mountSuspended(MemberTeamsGroupList, {
      props: { orgSlug: 'org-1', groups: GROUPS, limit: 100, unassignedCount: 2, ...props },
      global: { stubs },
    })
    await flushPromises()
    return wrapper
  }

  async function openCreate(wrapper: Awaited<ReturnType<typeof mountList>>, name: string) {
    await wrapper.find('[data-testid="group-create-open"]').trigger('click')
    await wrapper.find('[data-testid="group-form-name"]').setValue(name)
  }

  it('GL-01: 作成は名前を送り、changed を発火する', async () => {
    const wrapper = await mountList()
    await openCreate(wrapper, '北地区')
    await wrapper.find('[data-testid="group-form-submit"]').trigger('click')
    await flushPromises()
    expect(createTeamGroup).toHaveBeenCalledWith('org-1', { name: '北地区', description: undefined })
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('GL-02: 名前が空（空白のみ）なら送らない', async () => {
    const wrapper = await mountList()
    await openCreate(wrapper, '   ')
    await wrapper.find('[data-testid="group-form-submit"]').trigger('click')
    await flushPromises()
    expect(createTeamGroup).not.toHaveBeenCalled()
  })

  it('GL-03: 同名(ORG_065)は専用メッセージを出し、changed は発火しない', async () => {
    createTeamGroup.mockRejectedValue(apiError(409, 'ORG_065'))
    const wrapper = await mountList()
    await openCreate(wrapper, '東地区')
    await wrapper.find('[data-testid="group-form-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="group-form-error"]').attributes('data-error-code')).toBe('ORG_065')
    expect(wrapper.emitted('changed')).toBeUndefined()
  })

  it('GL-04: 上限(ORG_066)は専用メッセージ。上限に達していれば作成ボタンは無効', async () => {
    createTeamGroup.mockRejectedValue(apiError(422, 'ORG_066'))
    const wrapper = await mountList()
    await openCreate(wrapper, '北地区')
    await wrapper.find('[data-testid="group-form-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="group-form-error"]').attributes('data-error-code')).toBe('ORG_066')

    const full = await mountList({ limit: 2 })
    expect(full.find('[data-testid="group-create-open"]').attributes('disabled')).toBeDefined()
    expect(full.find('[data-testid="group-limit-reached"]').exists()).toBe(true)
  })

  it('GL-05: 改名は変更後の名前を送って changed を発火する', async () => {
    const wrapper = await mountList()
    await wrapper.find(`[data-testid="group-edit-${GROUP_A}"]`).trigger('click')
    expect((wrapper.find('[data-testid="group-form-name"]').element as HTMLInputElement).value).toBe('東地区')
    await wrapper.find('[data-testid="group-form-name"]').setValue('改名後')
    await wrapper.find('[data-testid="group-form-submit"]').trigger('click')
    await flushPromises()
    expect(updateTeamGroup).toHaveBeenCalledWith('org-1', GROUP_A, { name: '改名後', description: undefined })
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('GL-06: 削除は所属チーム件数つきの確認を挟み、確定で削除して changed を発火する', async () => {
    const wrapper = await mountList()
    await wrapper.find(`[data-testid="group-delete-${GROUP_A}"]`).trigger('click')
    expect(deleteTeamGroup).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="group-delete-message"]').text()).toContain('3')
    await wrapper.find('[data-testid="group-delete-confirm"]').trigger('click')
    await flushPromises()
    expect(deleteTeamGroup).toHaveBeenCalledWith('org-1', GROUP_A)
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('GL-07: 並び替え(↓)は全グループの ID を新しい順で送る', async () => {
    const wrapper = await mountList()
    await wrapper.find(`[data-testid="group-down-${GROUP_A}"]`).trigger('click')
    await flushPromises()
    expect(reorderTeamGroups).toHaveBeenCalledWith('org-1', [GROUP_B, GROUP_A])
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('GL-08: 先頭の↑・末尾の↓は無効', async () => {
    const wrapper = await mountList()
    expect(wrapper.find(`[data-testid="group-up-${GROUP_A}"]`).attributes('disabled')).toBeDefined()
    expect(wrapper.find(`[data-testid="group-down-${GROUP_B}"]`).attributes('disabled')).toBeDefined()
  })

  it('GL-09: 送信中の再クリックでは二重に作成しない', async () => {
    let resolve: (v: unknown) => void = () => {}
    createTeamGroup.mockReturnValue(new Promise((r) => { resolve = r }))
    const wrapper = await mountList()
    await openCreate(wrapper, '北地区')
    await wrapper.find('[data-testid="group-form-submit"]').trigger('click')
    await wrapper.find('[data-testid="group-form-submit"]').trigger('click')
    resolve(group('new', '北地区', 2))
    await flushPromises()
    expect(createTeamGroup).toHaveBeenCalledTimes(1)
  })

  it('GL-10: 機能 off(ORG_067)になっていたら changed を発火する（親が誘導カードへ切り替える）', async () => {
    createTeamGroup.mockRejectedValue(apiError(409, 'ORG_067'))
    const wrapper = await mountList()
    await openCreate(wrapper, '北地区')
    await wrapper.find('[data-testid="group-form-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })
})

describe('MemberTeamsGroupAssignments', () => {
  const TEAMS = [
    { id: 't1', slug: 'team-1', name: 'チーム1', teamGroup: { id: GROUP_A, name: '東地区', sortOrder: 0 } },
    { id: 't2', slug: 'team-2', name: 'チーム2' },
    { id: 't3', slug: 'team-3', name: 'チーム3' },
  ]

  beforeEach(() => {
    for (const fn of [listGroupTeams, assignTeamGroup, bulkAssignTeamGroup]) fn.mockReset()
    listGroupTeams.mockResolvedValue(TEAMS)
    assignTeamGroup.mockResolvedValue(undefined)
    bulkAssignTeamGroup.mockResolvedValue({ updatedCount: 2 })
  })

  async function mountAssign() {
    const wrapper = await mountSuspended(MemberTeamsGroupAssignments, {
      props: { orgSlug: 'org-1', groups: GROUPS },
      global: { stubs },
    })
    await flushPromises()
    return wrapper
  }

  it('GA-01: 加盟チームにグループ列が出て、グループなしは「未分類」になる', async () => {
    const wrapper = await mountAssign()
    expect(listGroupTeams).toHaveBeenCalledWith('org-1', {})
    const labels = wrapper.findAll('[data-testid="team-group-label"]').map(w => w.text())
    expect(labels).toEqual(['東地区', '未分類', '未分類'])
  })

  it('GA-02: 「未分類」で絞ると unassigned=true で取得する', async () => {
    const wrapper = await mountAssign()
    await wrapper.find('[data-testid="assign-filter"]').setValue('__unassigned__')
    await flushPromises()
    expect(listGroupTeams).toHaveBeenLastCalledWith('org-1', { unassigned: true })
  })

  it('GA-03: グループで絞ると teamGroupId で取得する', async () => {
    const wrapper = await mountAssign()
    await wrapper.find('[data-testid="assign-filter"]').setValue(GROUP_B)
    await flushPromises()
    expect(listGroupTeams).toHaveBeenLastCalledWith('org-1', { teamGroupId: GROUP_B })
  })

  it('GA-04: 単体割当は API を呼び、一覧を再取得して changed を発火する', async () => {
    const wrapper = await mountAssign()
    await wrapper.find('[data-testid="team-assign-team-2"]').setValue(GROUP_B)
    await flushPromises()
    expect(assignTeamGroup).toHaveBeenCalledWith('org-1', 'team-2', GROUP_B)
    expect(listGroupTeams).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('GA-05: 「未分類」を選ぶと groupId=null で割り当てる', async () => {
    const wrapper = await mountAssign()
    await wrapper.find('[data-testid="team-assign-team-1"]').setValue('__unassigned__')
    await flushPromises()
    expect(assignTeamGroup).toHaveBeenCalledWith('org-1', 'team-1', null)
  })

  it('GA-06: 一括割当は選択したチームだけを送る', async () => {
    const wrapper = await mountAssign()
    await wrapper.find('[data-testid="team-select-team-2"]').setValue(true)
    await wrapper.find('[data-testid="team-select-team-3"]').setValue(true)
    await wrapper.find('[data-testid="bulk-target"]').setValue(GROUP_B)
    await wrapper.find('[data-testid="bulk-submit"]').trigger('click')
    await flushPromises()
    expect(bulkAssignTeamGroup).toHaveBeenCalledWith('org-1', GROUP_B, ['team-2', 'team-3'])
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('GA-07: チーム未選択なら一括割当は押せない', async () => {
    const wrapper = await mountAssign()
    expect(wrapper.find('[data-testid="bulk-submit"]').attributes('disabled')).toBeDefined()
  })

  it('GA-08: 割当の失敗はエラー表示になり、成功に見せない', async () => {
    assignTeamGroup.mockRejectedValue(apiError(500, 'COMMON_500'))
    const wrapper = await mountAssign()
    await wrapper.find('[data-testid="team-assign-team-2"]').setValue(GROUP_B)
    await flushPromises()
    expect(wrapper.find('[data-testid="assign-error"]').exists()).toBe(true)
    expect(wrapper.emitted('changed')).toBeUndefined()
  })

  it('GA-09: 一括割当の 429 は専用メッセージを出す', async () => {
    bulkAssignTeamGroup.mockRejectedValue({ statusCode: 429, data: {} })
    const wrapper = await mountAssign()
    await wrapper.find('[data-testid="team-select-team-2"]').setValue(true)
    await wrapper.find('[data-testid="bulk-target"]').setValue(GROUP_B)
    await wrapper.find('[data-testid="bulk-submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="assign-error"]').attributes('data-error-kind')).toBe('rate-limited')
  })

  it('GA-10: 取得失敗はエラー表示、0 件は空表示', async () => {
    listGroupTeams.mockRejectedValue(apiError(500, 'COMMON_500'))
    const failed = await mountAssign()
    expect(failed.find('[data-testid="assign-load-error"]').exists()).toBe(true)
    expect(failed.find('[data-testid="assign-empty"]').exists()).toBe(false)

    listGroupTeams.mockResolvedValue([])
    const empty = await mountAssign()
    expect(empty.find('[data-testid="assign-empty"]').exists()).toBe(true)
  })

  it('GA-11: ORG_064（グループが消えていた）は changed を発火して親に再取得させる', async () => {
    assignTeamGroup.mockRejectedValue(apiError(404, 'ORG_064'))
    const wrapper = await mountAssign()
    await wrapper.find('[data-testid="team-assign-team-2"]').setValue(GROUP_B)
    await flushPromises()
    expect(wrapper.find('[data-testid="assign-error"]').attributes('data-error-kind')).toBe('group-missing')
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })
})
