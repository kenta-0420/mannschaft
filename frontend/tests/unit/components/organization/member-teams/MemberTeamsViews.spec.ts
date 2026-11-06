import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import type { TeamOrgAffiliation, TeamOrgAffiliationRestriction } from '~/composables/useOrgTeamMembershipApi'
import MemberTeamsApplicationsView from '~/components/organization/member-teams/MemberTeamsApplicationsView.vue'
import MemberTeamsInvitesView from '~/components/organization/member-teams/MemberTeamsInvitesView.vue'
import MemberTeamsRestrictionsView from '~/components/organization/member-teams/MemberTeamsRestrictionsView.vue'
import { primeStubs } from './stubs'

/**
 * F01.2.1 8-B — 申請・招待・制限の各 view の FE-UT。
 *
 * 検証観点:
 *   AV-01 申請一覧に行が並ぶ                       AV-02 取得失敗はエラー表示（空に見せない）
 *   AV-03 0 件なら空表示                            AV-04 承認ボタンで承認ダイアログが対象の申請で開く
 *   AV-05 承認後に一覧を再取得し changed/loaded を発火   AV-06 拒否後も同様
 *   AV-07 複数ページなら次ページを取得できる
 *   IVV-01 招待一覧に行が並ぶ                       IVV-02 取消は確認を挟み、確定で API を呼んで再取得
 *   IVV-03 取消の失敗はエラー表示                    IVV-04 「チームを招待」でダイアログが開き、招待後に再取得
 *   IVV-05 取得失敗はエラー表示
 *   RV-01 制限一覧に行が並び種別を出す               RV-02 解除で API を呼んで再取得
 *   RV-03 解除失敗はエラー表示                       RV-04 取得失敗はエラー表示 / 0 件は空表示
 */

const listApplications = vi.fn()
const listInvites = vi.fn()
const cancelInvite = vi.fn()
const listRestrictions = vi.fn()
const liftRestriction = vi.fn()

vi.mock('~/composables/useOrgTeamMembershipApi', () => ({
  useOrgTeamMembershipApi: () => ({
    listApplications,
    listInvites,
    cancelInvite,
    listRestrictions,
    liftRestriction,
  }),
}))

const dialogStub = (testid: string, doneEvent: string) => ({
  props: ['visible', 'application', 'orgSlug'],
  emits: ['update:visible', doneEvent],
  template: `<div v-if="visible" data-testid="${testid}" :data-app-id="application?.id"><button data-testid="${testid}-done" @click="$emit('${doneEvent}')" /></div>`,
})

const stubs = {
  ...primeStubs,
  TeamApplicationApproveDialog: dialogStub('stub-approve', 'approved'),
  TeamApplicationRejectDialog: dialogStub('stub-reject', 'rejected'),
  TeamInviteDialog: dialogStub('stub-invite', 'invited'),
}

function page<T>(items: T[], totalPages = 1) {
  return { items, total: items.length, totalPages }
}

function application(id: number, teamName: string): TeamOrgAffiliation {
  return {
    id,
    direction: 'TEAM_APPLICATION',
    status: 'PENDING',
    team: { slug: `team-${id}`, name: teamName },
    requestedAt: '2026-10-01T09:00:00Z',
  }
}

describe('MemberTeamsApplicationsView', () => {
  beforeEach(() => {
    listApplications.mockReset()
    listApplications.mockResolvedValue(page([application(1, 'チームA'), application(2, 'チームB')]))
  })

  async function mountView() {
    const wrapper = await mountSuspended(MemberTeamsApplicationsView, {
      props: { orgSlug: 'org-1' },
      global: { stubs },
    })
    await flushPromises()
    return wrapper
  }

  it('AV-01: 申請一覧に行が並ぶ', async () => {
    const wrapper = await mountView()
    expect(listApplications).toHaveBeenCalledWith('org-1', 0, expect.any(Number))
    expect(wrapper.findAll('[data-testid="application-row"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('チームA')
    expect(wrapper.emitted('loaded')?.at(-1)).toEqual([2])
  })

  it('AV-02: 取得失敗はエラー表示になり、空表示にはならない', async () => {
    listApplications.mockRejectedValue(new Error('403'))
    const wrapper = await mountView()
    expect(wrapper.find('[data-testid="applications-load-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="applications-empty"]').exists()).toBe(false)
  })

  it('AV-03: 0 件なら空表示', async () => {
    listApplications.mockResolvedValue(page([]))
    const wrapper = await mountView()
    expect(wrapper.find('[data-testid="applications-empty"]').exists()).toBe(true)
  })

  it('AV-04: 承認ボタンで、その申請に対する承認ダイアログが開く', async () => {
    const wrapper = await mountView()
    expect(wrapper.find('[data-testid="stub-approve"]').exists()).toBe(false)
    await wrapper.find('[data-testid="application-approve-2"]').trigger('click')
    const dialog = wrapper.find('[data-testid="stub-approve"]')
    expect(dialog.exists()).toBe(true)
    expect(dialog.attributes('data-app-id')).toBe('2')
  })

  it('AV-05: 承認後に一覧を再取得し、changed を発火する', async () => {
    const wrapper = await mountView()
    await wrapper.find('[data-testid="application-approve-1"]').trigger('click')
    listApplications.mockResolvedValue(page([application(2, 'チームB')]))
    await wrapper.find('[data-testid="stub-approve-done"]').trigger('click')
    await flushPromises()
    expect(listApplications).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('[data-testid="application-row"]')).toHaveLength(1)
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('AV-06: 拒否ボタンで拒否ダイアログが開き、拒否後に再取得して changed を発火する', async () => {
    const wrapper = await mountView()
    await wrapper.find('[data-testid="application-reject-1"]').trigger('click')
    expect(wrapper.find('[data-testid="stub-reject"]').attributes('data-app-id')).toBe('1')
    await wrapper.find('[data-testid="stub-reject-done"]').trigger('click')
    await flushPromises()
    expect(listApplications).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('AV-07: 複数ページなら次ページを取得できる', async () => {
    listApplications.mockResolvedValue(page([application(1, 'チームA')], 3))
    const wrapper = await mountView()
    await wrapper.find('[data-testid="pager-next"]').trigger('click')
    await flushPromises()
    expect(listApplications).toHaveBeenLastCalledWith('org-1', 1, expect.any(Number))
  })
})

describe('MemberTeamsInvitesView', () => {
  beforeEach(() => {
    listInvites.mockReset()
    listInvites.mockResolvedValue(page([application(11, 'チームX')]))
    cancelInvite.mockReset()
    cancelInvite.mockResolvedValue(undefined)
  })

  async function mountView() {
    const wrapper = await mountSuspended(MemberTeamsInvitesView, {
      props: { orgSlug: 'org-1' },
      global: { stubs },
    })
    await flushPromises()
    return wrapper
  }

  it('IVV-01: 招待一覧に行が並ぶ', async () => {
    const wrapper = await mountView()
    expect(wrapper.findAll('[data-testid="invite-row"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('チームX')
  })

  it('IVV-02: 取消は確認を挟み、確定で API を呼んで再取得する', async () => {
    const wrapper = await mountView()
    await wrapper.find('[data-testid="invite-cancel-team-11"]').trigger('click')
    expect(cancelInvite).not.toHaveBeenCalled()
    listInvites.mockResolvedValue(page([]))
    await wrapper.find('[data-testid="invite-cancel-confirm"]').trigger('click')
    await flushPromises()
    expect(cancelInvite).toHaveBeenCalledWith('org-1', 'team-11')
    expect(listInvites).toHaveBeenCalledTimes(2)
  })

  it('IVV-03: 取消の失敗はエラー表示になる', async () => {
    cancelInvite.mockRejectedValue(new Error('TEAM_070'))
    const wrapper = await mountView()
    await wrapper.find('[data-testid="invite-cancel-team-11"]').trigger('click')
    await wrapper.find('[data-testid="invite-cancel-confirm"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="invite-cancel-error"]').exists()).toBe(true)
  })

  it('IVV-04: 「チームを招待」でダイアログが開き、招待後に再取得する', async () => {
    const wrapper = await mountView()
    await wrapper.find('[data-testid="invite-open"]').trigger('click')
    expect(wrapper.find('[data-testid="stub-invite"]').exists()).toBe(true)
    await wrapper.find('[data-testid="stub-invite-done"]').trigger('click')
    await flushPromises()
    expect(listInvites).toHaveBeenCalledTimes(2)
  })

  it('IVV-05: 取得失敗はエラー表示になる', async () => {
    listInvites.mockRejectedValue(new Error('403'))
    const wrapper = await mountView()
    expect(wrapper.find('[data-testid="invites-load-error"]').exists()).toBe(true)
  })
})

describe('MemberTeamsRestrictionsView', () => {
  // reason は BE の列挙値（REJECTED / DECLINED / WITHDRAWN / CANCELLED）。自由文ではない（F01.2.1 §5.4）
  const restriction = (id: string, kind: string, reason = 'REJECTED'): TeamOrgAffiliationRestriction => ({
    id,
    kind,
    direction: 'TEAM_APPLY',
    team: { slug: `team-${id}`, name: `チーム${id}` },
    restrictedUntil: kind === 'COOLDOWN' ? '2026-11-01T00:00:00Z' : undefined,
    reason,
  })

  beforeEach(() => {
    listRestrictions.mockReset()
    listRestrictions.mockResolvedValue(page([restriction('r1', 'COOLDOWN'), restriction('r2', 'BLOCK')]))
    liftRestriction.mockReset()
    liftRestriction.mockResolvedValue(undefined)
  })

  async function mountView() {
    const wrapper = await mountSuspended(MemberTeamsRestrictionsView, {
      props: { orgSlug: 'org-1' },
      global: { stubs },
    })
    await flushPromises()
    return wrapper
  }

  it('RV-01: 制限一覧に行が並び、種別を出す', async () => {
    const wrapper = await mountView()
    expect(wrapper.findAll('[data-testid="restriction-row"]')).toHaveLength(2)
    expect(wrapper.find('[data-testid="restriction-kind-r1"]').attributes('data-kind')).toBe('COOLDOWN')
    expect(wrapper.find('[data-testid="restriction-kind-r2"]').attributes('data-kind')).toBe('BLOCK')
  })

  it('RV-02: 解除で API を呼んで再取得する', async () => {
    const wrapper = await mountView()
    listRestrictions.mockResolvedValue(page([restriction('r2', 'BLOCK')]))
    await wrapper.find('[data-testid="restriction-lift-r1"]').trigger('click')
    await flushPromises()
    expect(liftRestriction).toHaveBeenCalledWith('org-1', 'r1')
    expect(listRestrictions).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('[data-testid="restriction-row"]')).toHaveLength(1)
  })

  it('RV-03: 解除失敗はエラー表示になり、一覧は据え置く', async () => {
    liftRestriction.mockRejectedValue(new Error('500'))
    const wrapper = await mountView()
    await wrapper.find('[data-testid="restriction-lift-r1"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="restriction-lift-error"]').exists()).toBe(true)
    expect(listRestrictions).toHaveBeenCalledTimes(1)
  })

  it('RV-04: 取得失敗はエラー表示、0 件は空表示', async () => {
    listRestrictions.mockRejectedValue(new Error('403'))
    const failed = await mountView()
    expect(failed.find('[data-testid="restrictions-load-error"]').exists()).toBe(true)
    expect(failed.find('[data-testid="restrictions-empty"]').exists()).toBe(false)

    listRestrictions.mockResolvedValue(page([]))
    const empty = await mountView()
    expect(empty.find('[data-testid="restrictions-empty"]').exists()).toBe(true)
  })

  // テストの i18n は en ロケールで描画される（既存の種別表示も In cooldown / Blocked）
  it('RV-05: 理由は列挙値の生値ではなく、利用者向けのラベルで出す（実機E2E #4009 D-1）', async () => {
    listRestrictions.mockResolvedValue(page([
      restriction('r1', 'COOLDOWN', 'REJECTED'),
      restriction('r2', 'BLOCK', 'DECLINED'),
    ]))
    const wrapper = await mountView()
    const rows = wrapper.findAll('[data-testid="restriction-row"]')
    expect(rows[0]!.text()).toContain('Application rejected')
    expect(rows[0]!.text()).not.toContain('REJECTED')
    expect(rows[1]!.text()).toContain('Invitation declined')
    expect(rows[1]!.text()).not.toContain('DECLINED')
  })

  it('RV-06: 未知の理由コードは生値を出さず「その他の理由」にまとめる', async () => {
    listRestrictions.mockResolvedValue(page([restriction('r1', 'BLOCK', 'SOMETHING_NEW')]))
    const wrapper = await mountView()
    const row = wrapper.find('[data-testid="restriction-row"]')
    expect(row.text()).toContain('Other reason')
    expect(row.text()).not.toContain('SOMETHING_NEW')
  })
})
