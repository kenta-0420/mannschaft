import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import type { OrgTeamGroup } from '~/composables/useOrgTeamMembershipApi'
import MemberTeamsGroupsView from '~/components/organization/member-teams/MemberTeamsGroupsView.vue'
import MemberTeamsGroupList from '~/components/organization/member-teams/MemberTeamsGroupList.vue'
import { GROUP_A, GROUP_B, primeStubs } from './stubs'

/**
 * F01.2.1 8-C — チームグループ管理 view（member-teams?view=groups）の FE-UT。
 *
 * 検証観点（設計書 AC-L04 / AC-G01 / AC-F08 / AC-G143）:
 *   GV-01 グループ一覧と未分類件数が出る                 GV-02 機能 off（一覧が null）は誘導カードと設定への導線
 *   GV-03 取得失敗はエラー表示（機能 off や空に見せない）   GV-04 機能 off のときは管理 UI を出さない
 *   GV-05 組織が切り替わったとき、古い組織の遅れた応答が新しい組織の一覧を上書きしない
 *   GL-01 作成: 名前を送って changed 発火                 GL-02 名前が空なら送らない
 *   GL-03 同名(ORG_065)は専用メッセージ                   GL-04 上限(ORG_066)は専用メッセージ。上限到達で作成ボタン無効
 *   GL-05 改名: 変更を送って changed 発火                 GL-06 削除は所属件数つきの確認を挟み、確定で削除
 *   GL-07 並び替え(↑↓)は全 ID を新しい順で送る            GL-08 先頭の↑・末尾の↓は無効
 *   GL-09 二重送信しない                                   GL-10 機能 off(ORG_067)になったら changed を発火
 */

const listTeamGroupList = vi.fn()
const createTeamGroup = vi.fn()
const updateTeamGroup = vi.fn()
const deleteTeamGroup = vi.fn()
const reorderTeamGroups = vi.fn()

vi.mock('~/composables/useOrgTeamMembershipApi', () => ({
  useOrgTeamMembershipApi: () => ({
    listTeamGroupList,
    createTeamGroup,
    updateTeamGroup,
    deleteTeamGroup,
    reorderTeamGroups,
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
    listTeamGroupList.mockResolvedValue(groupList())
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

  it('GV-04: 機能 off のときは管理 UI（作成）を出さない', async () => {
    listTeamGroupList.mockResolvedValue(null)
    const wrapper = await mountView()
    expect(wrapper.find('[data-testid="group-create-open"]').exists()).toBe(false)
  })

  it('GV-05: 組織が切り替わったら、古い組織の遅れた応答は捨てて新しい組織の一覧を出す', async () => {
    let resolveOld: (v: unknown) => void = () => {}
    listTeamGroupList.mockReset()
    listTeamGroupList
      .mockReturnValueOnce(new Promise((r) => { resolveOld = r }))
      .mockResolvedValueOnce(groupList([group(GROUP_B, '新組織のグループ', 0)], 0))
    const wrapper = await mountSuspended(MemberTeamsGroupsView, {
      props: { orgSlug: 'org-old' },
      global: { stubs },
    })
    await wrapper.setProps({ orgSlug: 'org-new' })
    await flushPromises()
    expect(wrapper.text()).toContain('新組織のグループ')
    // 古い組織の応答が最後に届いても、画面は新しい組織のまま
    resolveOld(groupList([group(GROUP_A, '旧組織のグループ', 0)], 9))
    await flushPromises()
    expect(wrapper.text()).toContain('新組織のグループ')
    expect(wrapper.text()).not.toContain('旧組織のグループ')
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
