import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ShiftDetailTabs from './ShiftDetailTabs.vue'

/**
 * CMP-261010-1432 FE-a: /shift/{id} のタブ nav（4ページ共有）の AC。
 *   AC-K1 変更依頼・PDF のリンクがあり、管理者にはボードも出る。一般メンバーにはボードが出ない
 *   AC-K2 myTeams にそのチームが無い利用者には teams 配下へのリンクを出さない
 *   AC-K3 SUPPORTER には PDF を出さない
 */
const state = vi.hoisted(() => ({
  myTeams: [] as Array<{ id: number; slug: string; role: string }>,
}))

mockNuxtImport('useTeamStore', () => () => ({ get myTeams() { return state.myTeams } }))
mockNuxtImport('useShiftPdf', () => () => ({ isDownloading: false, download: vi.fn() }))

async function mountTabs(role: string | null, teamId: number | null = 12) {
  state.myTeams = role === null ? [] : [{ id: 12, slug: 'team-a', role }]
  return mountSuspended(ShiftDetailTabs, { props: { scheduleId: 10, teamId, current: 'overview' } })
}

function hrefOf(wrapper: Awaited<ReturnType<typeof mountTabs>>, testid: string): string | undefined {
  return wrapper.find(`[data-testid="${testid}"]`).attributes('href')
}

beforeEach(() => {
  state.myTeams = []
})

describe('ShiftDetailTabs', () => {
  it('AC-K1: 管理者にはボード・変更依頼のリンクと PDF が出て、4つの既存タブも出る', async () => {
    const wrapper = await mountTabs('ADMIN')
    expect(hrefOf(wrapper, 'shift-tab-board')).toBe('/teams/team-a/shifts/10/board')
    expect(hrefOf(wrapper, 'shift-tab-change-requests')).toBe('/teams/team-a/shifts/10/change-requests')
    expect(wrapper.find('[data-testid="shift-tab-pdf"]').exists()).toBe(true)
    // 現在地（overview）はリンクでなく、ほかの3タブはリンク
    expect(hrefOf(wrapper, 'shift-tab-edit')).toBe('/shift/10/edit')
    expect(hrefOf(wrapper, 'shift-tab-requests')).toBe('/shift/10/requests')
    expect(hrefOf(wrapper, 'shift-tab-constraints')).toBe('/shift/10/work-constraints')
    expect(wrapper.find('[data-testid="shift-tab-overview"]').attributes('href')).toBeUndefined()
  })

  it('AC-K1: 一般メンバーにはボードを出さず、変更依頼・PDF は出す。希望一覧・勤務制約も出さない', async () => {
    const wrapper = await mountTabs('MEMBER')
    expect(wrapper.find('[data-testid="shift-tab-board"]').exists()).toBe(false)
    expect(hrefOf(wrapper, 'shift-tab-change-requests')).toBe('/teams/team-a/shifts/10/change-requests')
    expect(wrapper.find('[data-testid="shift-tab-pdf"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="shift-tab-requests"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="shift-tab-constraints"]').exists()).toBe(false)
  })

  it('AC-K1: DEPUTY_ADMIN にもボードが出る', async () => {
    const wrapper = await mountTabs('DEPUTY_ADMIN')
    expect(wrapper.find('[data-testid="shift-tab-board"]').exists()).toBe(true)
  })

  it('AC-K2: myTeams にそのチームが無い利用者には teams 配下へのリンクを出さない', async () => {
    const wrapper = await mountTabs(null)
    expect(wrapper.find('[data-testid="shift-tab-board"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="shift-tab-change-requests"]').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('/teams/')
  })

  it('AC-K2: シフト表が未取得（teamId=null）のときも teams 配下へのリンクを出さない', async () => {
    const wrapper = await mountTabs('ADMIN', null)
    expect(wrapper.html()).not.toContain('/teams/')
  })

  it('AC-K3: SUPPORTER には PDF を出さない', async () => {
    const wrapper = await mountTabs('SUPPORTER')
    expect(wrapper.find('[data-testid="shift-tab-pdf"]').exists()).toBe(false)
  })
})
