import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import TeamAffiliationsOrganizationsView from '~/components/team/affiliations/TeamAffiliationsOrganizationsView.vue'
import TeamAffiliationsInvitesView from '~/components/team/affiliations/TeamAffiliationsInvitesView.vue'
import TeamAffiliationsApplicationsView from '~/components/team/affiliations/TeamAffiliationsApplicationsView.vue'
import TeamAffiliationsRestrictionsView from '~/components/team/affiliations/TeamAffiliationsRestrictionsView.vue'
import { primeStubs } from '../../organization/member-teams/stubs'

/**
 * F01.2.1 8-D — チーム側「所属組織」画面の各 view の FE-UT。
 *
 *   OV-01 所属組織が並び、グループ名が出る        OV-02 取得失敗はエラー表示（空に見せない）/ 0件は空表示
 *   OV-03 離脱は確認を挟み、確定で API を呼んで再取得（AC-E01）   OV-04 離脱失敗は確認内にエラーを残す
 *   OV-05 離脱の二重送信を防ぐ                    OV-06 古い応答が新しい表示を上書きしない
 *   IV-01 受信招待が並ぶ                          IV-02 承諾で API を呼び再取得・changed 発火（AC-D02）
 *   IV-03 辞退はダイアログで block を選べる（AC-D03）  IV-04 承諾の失敗はエラー表示し、再取得する（古い行を残さない）
 *   IV-05 取得失敗はエラー表示 / 0件は空表示       IV-06 loaded で total を通知
 *   AV-01 申請中が並ぶ / AV-02 取下げは確認を挟む  AV-03 取下げ失敗はエラー
 *   RV-01 制限が並ぶ / RV-02 解除で API を呼び再取得  RV-03 解除失敗はエラー
 */

const listOrganizations = vi.fn()
const leaveOrganization = vi.fn()
const listInvites = vi.fn()
const acceptInvite = vi.fn()
const declineInvite = vi.fn()
const listApplications = vi.fn()
const withdrawApplication = vi.fn()
const listRestrictions = vi.fn()
const liftRestriction = vi.fn()

vi.mock('~/composables/useTeamAffiliationApi', () => ({
  useTeamAffiliationApi: () => ({
    listOrganizations,
    leaveOrganization,
    listInvites,
    acceptInvite,
    declineInvite,
    listApplications,
    withdrawApplication,
    listRestrictions,
    liftRestriction,
  }),
}))

const stubs = { ...primeStubs }

function page<T>(items: T[], totalPages = 1) {
  return { items, total: items.length, totalPages }
}

async function mountView(component: object, props: Record<string, unknown> = { teamSlug: 'team-1' }) {
  const wrapper = await mountSuspended(component, { props, global: { stubs } })
  await flushPromises()
  return wrapper
}

describe('TeamAffiliationsOrganizationsView', () => {
  beforeEach(() => {
    listOrganizations.mockReset()
    leaveOrganization.mockReset()
    listOrganizations.mockResolvedValue([
      { id: 'o1', slug: 'org-a', name: '組織A', teamGroup: { id: 'g1', name: '2008年度' } },
      { id: 'o2', slug: 'org-b', name: '組織B', teamGroup: null },
    ])
  })

  it('OV-01: 所属組織とグループ名が並ぶ', async () => {
    const w = await mountView(TeamAffiliationsOrganizationsView)
    const rows = w.findAll('[data-testid="org-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('組織A')
    expect(rows[0]!.text()).toContain('2008年度')
  })

  it('OV-02: 取得失敗はエラー、0件は空表示', async () => {
    listOrganizations.mockRejectedValueOnce(new Error('boom'))
    const failed = await mountView(TeamAffiliationsOrganizationsView)
    expect(failed.find('[data-testid="organizations-load-error"]').exists()).toBe(true)
    expect(failed.find('[data-testid="organizations-empty"]').exists()).toBe(false)

    listOrganizations.mockResolvedValueOnce([])
    const empty = await mountView(TeamAffiliationsOrganizationsView)
    expect(empty.find('[data-testid="organizations-empty"]').exists()).toBe(true)
  })

  it('OV-03: 離脱は確認を挟み、確定で API を呼んで再取得し changed を発火', async () => {
    leaveOrganization.mockResolvedValue(undefined)
    const w = await mountView(TeamAffiliationsOrganizationsView)
    await w.find('[data-testid="org-leave-org-a"]').trigger('click')
    expect(leaveOrganization).not.toHaveBeenCalled()
    await w.find('[data-testid="org-leave-confirm"]').trigger('click')
    await flushPromises()
    expect(leaveOrganization).toHaveBeenCalledWith('team-1', 'org-a')
    expect(listOrganizations).toHaveBeenCalledTimes(2)
    expect(w.emitted('changed')).toHaveLength(1)
  })

  it('OV-04: 離脱失敗は確認内にエラーを残す（離脱できたように見せない）', async () => {
    leaveOrganization.mockRejectedValue(new Error('403'))
    const w = await mountView(TeamAffiliationsOrganizationsView)
    await w.find('[data-testid="org-leave-org-a"]').trigger('click')
    await w.find('[data-testid="org-leave-confirm"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="org-leave-error"]').exists()).toBe(true)
    expect(w.emitted('changed')).toBeUndefined()
  })

  it('OV-05: 離脱の二重送信を防ぐ', async () => {
    let resolve!: () => void
    leaveOrganization.mockReturnValue(new Promise<void>((r) => { resolve = r }))
    const w = await mountView(TeamAffiliationsOrganizationsView)
    await w.find('[data-testid="org-leave-org-a"]').trigger('click')
    const confirm = w.find('[data-testid="org-leave-confirm"]')
    await confirm.trigger('click')
    await confirm.trigger('click')
    expect(leaveOrganization).toHaveBeenCalledTimes(1)
    resolve()
    await flushPromises()
  })

  it('OV-06: チームを切り替えたとき、古い応答が新しい表示を上書きしない', async () => {
    let resolveOld!: (v: unknown) => void
    listOrganizations.mockReset()
    listOrganizations
      .mockReturnValueOnce(new Promise((r) => { resolveOld = r }))
      .mockResolvedValueOnce([{ id: 'o9', slug: 'org-new', name: '新しい組織', teamGroup: null }])
    const w = await mountSuspended(TeamAffiliationsOrganizationsView, {
      props: { teamSlug: 'team-old' },
      global: { stubs },
    })
    await w.setProps({ teamSlug: 'team-new' })
    await flushPromises()
    resolveOld([{ id: 'o1', slug: 'org-old', name: '古い組織', teamGroup: null }])
    await flushPromises()
    const text = w.text()
    expect(text).toContain('新しい組織')
    expect(text).not.toContain('古い組織')
  })
})

describe('TeamAffiliationsInvitesView', () => {
  const invite = (id: number, org: string) => ({
    id,
    direction: 'ORG_INVITE',
    status: 'PENDING',
    organization: { slug: `org-${id}`, name: org },
    teamGroup: { id: 'g', name: '東地区' },
    message: 'よろしく',
    requestedAt: '2026-10-01T09:00:00Z',
    expiresAt: '2026-11-30T09:00:00Z',
  })

  beforeEach(() => {
    listInvites.mockReset()
    acceptInvite.mockReset()
    declineInvite.mockReset()
    listInvites.mockResolvedValue(page([invite(1, '組織A'), invite(2, '組織B')]))
  })

  it('IV-01: 受信招待が並ぶ', async () => {
    const w = await mountView(TeamAffiliationsInvitesView)
    const rows = w.findAll('[data-testid="invite-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('組織A')
  })

  it('IV-02: 承諾で API を呼び再取得し changed を発火', async () => {
    acceptInvite.mockResolvedValue(undefined)
    const w = await mountView(TeamAffiliationsInvitesView)
    await w.find('[data-testid="invite-accept-1"]').trigger('click')
    await flushPromises()
    expect(acceptInvite).toHaveBeenCalledWith('team-1', 1)
    expect(listInvites).toHaveBeenCalledTimes(2)
    expect(w.emitted('changed')).toHaveLength(1)
  })

  it('IV-03: 辞退はダイアログで「今後受け付けない」を選べる', async () => {
    declineInvite.mockResolvedValue(undefined)
    const w = await mountView(TeamAffiliationsInvitesView)
    await w.find('[data-testid="invite-decline-2"]').trigger('click')
    expect(declineInvite).not.toHaveBeenCalled()
    await w.find('[data-testid="invite-decline-block"]').setValue(true)
    await w.find('[data-testid="invite-decline-confirm"]').trigger('click')
    await flushPromises()
    expect(declineInvite).toHaveBeenCalledWith('team-1', 2, true)
    expect(listInvites).toHaveBeenCalledTimes(2)
  })

  it('IV-03b: block を選ばなければ false で辞退する', async () => {
    declineInvite.mockResolvedValue(undefined)
    const w = await mountView(TeamAffiliationsInvitesView)
    await w.find('[data-testid="invite-decline-1"]').trigger('click')
    await w.find('[data-testid="invite-decline-confirm"]').trigger('click')
    await flushPromises()
    expect(declineInvite).toHaveBeenCalledWith('team-1', 1, false)
  })

  it('IV-04: 承諾の失敗はエラーを出し、一覧を取り直す', async () => {
    acceptInvite.mockRejectedValue(new Error('404'))
    const w = await mountView(TeamAffiliationsInvitesView)
    await w.find('[data-testid="invite-accept-1"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="invite-action-error"]').exists()).toBe(true)
    expect(listInvites).toHaveBeenCalledTimes(2)
    expect(w.emitted('changed')).toBeUndefined()
  })

  it('IV-05: 取得失敗はエラー、0件は空表示', async () => {
    listInvites.mockRejectedValueOnce(new Error('boom'))
    const failed = await mountView(TeamAffiliationsInvitesView)
    expect(failed.find('[data-testid="invites-load-error"]').exists()).toBe(true)
    listInvites.mockResolvedValueOnce(page([]))
    const empty = await mountView(TeamAffiliationsInvitesView)
    expect(empty.find('[data-testid="invites-empty"]').exists()).toBe(true)
  })

  it('IV-06: 取得できたら total を loaded で通知する', async () => {
    const w = await mountView(TeamAffiliationsInvitesView)
    expect(w.emitted('loaded')?.[0]).toEqual([2])
  })
})

describe('TeamAffiliationsApplicationsView', () => {
  beforeEach(() => {
    listApplications.mockReset()
    withdrawApplication.mockReset()
    listApplications.mockResolvedValue(
      page([{ id: 7, status: 'PENDING', direction: 'TEAM_APPLICATION', organization: { slug: 'org-x', name: '組織X' }, requestedAt: '2026-10-01T09:00:00Z' }]),
    )
  })

  it('AV-01/02: 申請中が並び、取下げは確認を挟んで API を呼び再取得', async () => {
    withdrawApplication.mockResolvedValue(undefined)
    const w = await mountView(TeamAffiliationsApplicationsView)
    expect(w.findAll('[data-testid="application-row"]')).toHaveLength(1)
    await w.find('[data-testid="application-withdraw-7"]').trigger('click')
    expect(withdrawApplication).not.toHaveBeenCalled()
    await w.find('[data-testid="application-withdraw-confirm"]').trigger('click')
    await flushPromises()
    expect(withdrawApplication).toHaveBeenCalledWith('team-1', 7)
    expect(listApplications).toHaveBeenCalledTimes(2)
  })

  it('AV-03: 取下げ失敗は確認内にエラー', async () => {
    withdrawApplication.mockRejectedValue(new Error('409'))
    const w = await mountView(TeamAffiliationsApplicationsView)
    await w.find('[data-testid="application-withdraw-7"]').trigger('click')
    await w.find('[data-testid="application-withdraw-confirm"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="application-withdraw-error"]').exists()).toBe(true)
  })

  it('AV-04: 取得失敗はエラー / 0件は空表示', async () => {
    listApplications.mockRejectedValueOnce(new Error('boom'))
    const failed = await mountView(TeamAffiliationsApplicationsView)
    expect(failed.find('[data-testid="applications-load-error"]').exists()).toBe(true)
    listApplications.mockResolvedValueOnce(page([]))
    const empty = await mountView(TeamAffiliationsApplicationsView)
    expect(empty.find('[data-testid="applications-empty"]').exists()).toBe(true)
  })
})

describe('TeamAffiliationsRestrictionsView', () => {
  beforeEach(() => {
    listRestrictions.mockReset()
    liftRestriction.mockReset()
    listRestrictions.mockResolvedValue(
      page([{ id: 'r-1', kind: 'BLOCK', direction: 'ORG_INVITE', organization: { slug: 'org-x', name: '組織X' }, createdAt: '2026-10-01T09:00:00Z' }]),
    )
  })

  it('RV-01/02: 制限が並び、解除で API を呼び再取得', async () => {
    liftRestriction.mockResolvedValue(undefined)
    const w = await mountView(TeamAffiliationsRestrictionsView)
    expect(w.findAll('[data-testid="restriction-row"]')).toHaveLength(1)
    await w.find('[data-testid="restriction-lift-r-1"]').trigger('click')
    await flushPromises()
    expect(liftRestriction).toHaveBeenCalledWith('team-1', 'r-1')
    expect(listRestrictions).toHaveBeenCalledTimes(2)
  })

  it('RV-03: 解除失敗はエラーを出す', async () => {
    liftRestriction.mockRejectedValue(new Error('404'))
    const w = await mountView(TeamAffiliationsRestrictionsView)
    await w.find('[data-testid="restriction-lift-r-1"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="restriction-action-error"]').exists()).toBe(true)
  })

  it('RV-04: 取得失敗はエラー / 0件は空表示', async () => {
    listRestrictions.mockRejectedValueOnce(new Error('boom'))
    const failed = await mountView(TeamAffiliationsRestrictionsView)
    expect(failed.find('[data-testid="restrictions-load-error"]').exists()).toBe(true)
    listRestrictions.mockResolvedValueOnce(page([]))
    const empty = await mountView(TeamAffiliationsRestrictionsView)
    expect(empty.find('[data-testid="restrictions-empty"]').exists()).toBe(true)
  })
})
