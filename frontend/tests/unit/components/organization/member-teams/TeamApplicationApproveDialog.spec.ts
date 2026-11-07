import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import type { TeamOrgAffiliation } from '~/composables/useOrgTeamMembershipApi'
import { UNASSIGNED_GROUP_VALUE } from '~/utils/memberTeamsView'
import TeamApplicationApproveDialog from '~/components/organization/member-teams/TeamApplicationApproveDialog.vue'
import { GROUP_A, GROUP_B, primeStubs } from './stubs'

/**
 * F01.2.1 8-B — 加盟申請の承認ダイアログ（TeamApplicationApproveDialog）の FE-UT。
 *
 * 検証観点（AC-C01〜C03 の UI 側）:
 *   AP-01 希望グループが初期選択で、変えずに承認すると overrideGroup=false
 *   AP-02 別のグループを選ぶと overrideGroup=true と選んだ groupId、「希望 → 承認時」の表示が出る
 *   AP-03 「未分類」を選ぶと overrideGroup=true・groupId なし
 *   AP-04 希望グループなしの申請は初期が未分類で、そのまま承認すると overrideGroup=false
 *   AP-05 グループ機能 off（一覧が null）ならグループ欄を出さず overrideGroup=false
 *   AP-06 承認成功で approved を発火して閉じる
 *   AP-07 承認失敗はエラー表示で閉じない（握りつぶさない）
 *   AP-08 グループ一覧の取得失敗はエラー表示し、グループ欄は出さない（承認自体は希望のままで可能）
 */

const approveApplication = vi.fn()
const listTeamGroups = vi.fn()

vi.mock('~/composables/useOrgTeamMembershipApi', () => ({
  useOrgTeamMembershipApi: () => ({ approveApplication, listTeamGroups }),
}))

function makeApplication(over: Partial<TeamOrgAffiliation> = {}): TeamOrgAffiliation {
  return {
    id: 101,
    direction: 'TEAM_APPLICATION',
    status: 'PENDING',
    team: { slug: 'team-a', name: 'チームA' },
    teamGroup: { id: GROUP_A, name: '平成20年度' },
    message: 'よろしくお願いします',
    ...over,
  }
}

const GROUPS = [
  { id: GROUP_A, name: '平成20年度', teamCount: 3 },
  { id: GROUP_B, name: '平成21年度', teamCount: 2 },
]

async function mountDialog(application: TeamOrgAffiliation = makeApplication()) {
  const wrapper = await mountSuspended(TeamApplicationApproveDialog, {
    props: { visible: true, orgSlug: 'org-1', application },
    global: { stubs: primeStubs },
  })
  await flushPromises()
  return wrapper
}

type Wrapper = Awaited<ReturnType<typeof mountDialog>>
const submit = (w: Wrapper) => w.find('[data-testid="approve-submit"]')

describe('TeamApplicationApproveDialog', () => {
  beforeEach(() => {
    approveApplication.mockReset()
    approveApplication.mockResolvedValue(undefined)
    listTeamGroups.mockReset()
    listTeamGroups.mockResolvedValue(GROUPS)
  })

  it('AP-01: 希望グループが初期選択で、変えずに承認すると overrideGroup=false', async () => {
    const wrapper = await mountDialog()
    const select = wrapper.find('[data-testid="approve-group-select"]')
    expect((select.element as HTMLSelectElement).value).toBe(GROUP_A)
    expect(wrapper.find('[data-testid="approve-group-change"]').exists()).toBe(false)
    await submit(wrapper).trigger('click')
    await flushPromises()
    expect(approveApplication).toHaveBeenCalledWith('org-1', 101, { overrideGroup: false })
  })

  it('AP-02: 別のグループを選ぶと overrideGroup=true と groupId を送り、変更表示が出る', async () => {
    const wrapper = await mountDialog()
    await wrapper.find('[data-testid="approve-group-select"]').setValue(GROUP_B)
    expect(wrapper.find('[data-testid="approve-group-change"]').exists()).toBe(true)
    await submit(wrapper).trigger('click')
    await flushPromises()
    expect(approveApplication).toHaveBeenCalledWith('org-1', 101, { overrideGroup: true, groupId: GROUP_B })
  })

  it('AP-03: 「未分類」を選ぶと overrideGroup=true・groupId なし', async () => {
    const wrapper = await mountDialog()
    await wrapper.find('[data-testid="approve-group-select"]').setValue(UNASSIGNED_GROUP_VALUE)
    await submit(wrapper).trigger('click')
    await flushPromises()
    const body = approveApplication.mock.calls[0]![2]
    expect(body.overrideGroup).toBe(true)
    expect(body.groupId).toBeUndefined()
  })

  it('AP-04: 希望グループなしの申請は初期が未分類で、そのまま承認すると overrideGroup=false', async () => {
    const wrapper = await mountDialog(makeApplication({ teamGroup: undefined }))
    const select = wrapper.find('[data-testid="approve-group-select"]')
    expect((select.element as HTMLSelectElement).value).toBe(UNASSIGNED_GROUP_VALUE)
    await submit(wrapper).trigger('click')
    await flushPromises()
    expect(approveApplication).toHaveBeenCalledWith('org-1', 101, { overrideGroup: false })
  })

  it('AP-05: グループ機能 off（一覧が null）ならグループ欄を出さず overrideGroup=false', async () => {
    listTeamGroups.mockResolvedValue(null)
    const wrapper = await mountDialog(makeApplication({ teamGroup: undefined }))
    expect(wrapper.find('[data-testid="approve-group-select"]').exists()).toBe(false)
    await submit(wrapper).trigger('click')
    await flushPromises()
    expect(approveApplication).toHaveBeenCalledWith('org-1', 101, { overrideGroup: false })
  })

  it('AP-06: 承認成功で approved を発火し、visible を false にする', async () => {
    const wrapper = await mountDialog()
    await submit(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.emitted('approved')).toHaveLength(1)
    expect(wrapper.emitted('update:visible')?.at(-1)).toEqual([false])
  })

  it('AP-07: 承認失敗はエラー表示になり、閉じず、approved も発火しない', async () => {
    approveApplication.mockRejectedValue(new Error('TEAM_072'))
    const wrapper = await mountDialog()
    await submit(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="approve-error"]').exists()).toBe(true)
    expect(wrapper.emitted('approved')).toBeUndefined()
    expect(wrapper.emitted('update:visible')).toBeUndefined()
  })

  it('AP-08: グループ一覧の取得失敗はエラー表示し、グループ欄は出さない', async () => {
    listTeamGroups.mockRejectedValue(new Error('network'))
    const wrapper = await mountDialog()
    expect(wrapper.find('[data-testid="approve-groups-load-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="approve-group-select"]').exists()).toBe(false)
    await submit(wrapper).trigger('click')
    await flushPromises()
    expect(approveApplication).toHaveBeenCalledWith('org-1', 101, { overrideGroup: false })
  })
})
