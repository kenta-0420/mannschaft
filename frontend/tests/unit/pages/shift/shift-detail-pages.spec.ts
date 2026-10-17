import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import ShiftDetailIndex from '~/pages/shift/[id]/index.vue'
import ShiftDetailEdit from '~/pages/shift/[id]/edit.vue'
import ShiftDetailRequests from '~/pages/shift/[id]/requests.vue'
import ShiftDetailWorkConstraints from '~/pages/shift/[id]/work-constraints.vue'
import type { ShiftScheduleResponse } from '~/types/shift'

/**
 * CMP-261010-1432 FE-a: /shift/{id} 系4ページの AC。
 *   AC-E1 取得 403 で DashboardErrorState(forbidden)。本体（タブ・フォーム）を描画しない
 *   AC-E2 404 は再試行ボタンを出さない
 *   AC-E3 5xx は再試行ボタンが出て、押すと初回と同じ取得をやり直す
 *   AC-K1 4ページとも共有タブ nav を出す
 *   AC-N1〜N3,N5 勤務制約: 名前でメンバーを選ぶ・既存除外・表示名・全員に到達できる
 */
const mocks = vi.hoisted(() => ({
  getSchedule: vi.fn(),
  listSlots: vi.fn(),
  listPositions: vi.fn(),
  listRequests: vi.fn(),
  getRequestSummary: vi.fn(),
  listConstraints: vi.fn(),
  getTeamDefault: vi.fn(),
  getAllMembers: vi.fn(),
  getMembers: vi.fn(),
  handleApiError: vi.fn(),
}))

mockNuxtImport('useRoute', () => () => ({ params: { id: '10' }, path: '/shift/10' }))
mockNuxtImport('useTeamStore', () => () => ({
  myTeams: [{ id: 12, slug: 'team-a', name: 'A', role: 'ADMIN' }],
  fetchMyTeams: async () => {},
}))
mockNuxtImport('useShiftApi', () => () => ({
  getSchedule: mocks.getSchedule,
  updateSchedule: vi.fn(),
  transitionStatus: vi.fn(),
  deleteSchedule: vi.fn(),
}))
mockNuxtImport('useShiftSlotApi', () => () => ({
  listSlots: mocks.listSlots,
  deleteSlot: vi.fn(),
  bulkCreateSlots: vi.fn(),
}))
mockNuxtImport('useShiftPositionApi', () => () => ({ listPositions: mocks.listPositions }))
mockNuxtImport('useShiftRequestApi', () => () => ({
  listRequests: mocks.listRequests,
  getRequestSummary: mocks.getRequestSummary,
}))
mockNuxtImport('useMemberWorkConstraintApi', () => () => ({
  listConstraints: mocks.listConstraints,
  getTeamDefault: mocks.getTeamDefault,
  upsertConstraint: vi.fn(),
  upsertTeamDefault: vi.fn(),
  deleteConstraint: vi.fn(),
  deleteTeamDefault: vi.fn(),
}))
mockNuxtImport('useTeamApi', () => () => ({
  getAllMembers: mocks.getAllMembers,
  getMembers: mocks.getMembers,
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn() }))
mockNuxtImport('useShiftPdf', () => () => ({ isDownloading: false, download: vi.fn() }))

const schedule: ShiftScheduleResponse = {
  id: 10,
  teamId: 12,
  content: { title: '10月第1週', periodType: 'WEEKLY', note: null },
  period: { startDate: '2026-10-01', endDate: '2026-10-07', requestDeadline: null },
  status: { status: 'COLLECTING', publishedAt: null, publishedBy: null },
  audit: { createdBy: 1, createdAt: '', updatedAt: '' },
}

const PAGES = [
  // [名前, コンポーネント, 二段目の取得 API（getSchedule の次に呼ばれるもの）]
  ['index', ShiftDetailIndex, mocks.listSlots],
  ['edit', ShiftDetailEdit, mocks.listSlots],
  ['requests', ShiftDetailRequests, mocks.listRequests],
  ['work-constraints', ShiftDetailWorkConstraints, mocks.listConstraints],
] as const

async function mountPage(component: (typeof PAGES)[number][1]) {
  const wrapper = await mountSuspended(component)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  document.body.innerHTML = ''
  Object.values(mocks).forEach(m => m.mockReset())
  mocks.getSchedule.mockResolvedValue(schedule)
  mocks.listSlots.mockResolvedValue([])
  mocks.listPositions.mockResolvedValue([])
  mocks.listRequests.mockResolvedValue([])
  mocks.getRequestSummary.mockResolvedValue({
    totalMembers: 0, submittedCount: 0, pendingCount: 0,
    preferredCount: 0, availableCount: 0, weakRestCount: 0, strongRestCount: 0, absoluteRestCount: 0,
  })
  mocks.listConstraints.mockResolvedValue([])
  mocks.getTeamDefault.mockResolvedValue(null)
  mocks.getAllMembers.mockResolvedValue({ data: [] })
})

describe.each(PAGES)('/shift/{id} %s の取得失敗（AC-E1〜E3）', (_name, component, secondary) => {
  it('対照: 取得成功のときはエラー状態を出さず、共有タブ nav を出す（AC-K1）', async () => {
    const wrapper = await mountPage(component)
    expect(wrapper.find('[data-testid="shift-load-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="shift-tab-overview"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="shift-tab-change-requests"]').exists()).toBe(true)
  })

  it('AC-E1: シフト表取得が 403 なら forbidden を描画し、タブ・本体を描画しない', async () => {
    mocks.getSchedule.mockRejectedValue({ statusCode: 403 })
    const wrapper = await mountPage(component)
    const state = wrapper.find('[data-testid="shift-load-error"]')
    expect(state.exists()).toBe(true)
    expect(state.text()).toContain("This page isn't available for this account")
    expect(wrapper.find('[data-testid="shift-tab-overview"]').exists()).toBe(false)
  })

  it('AC-E1: 二段目の取得（スロット・希望・勤務制約）が 403 でも空状態に倒さず forbidden を描画する', async () => {
    secondary.mockRejectedValue({ statusCode: 403 })
    const wrapper = await mountPage(component)
    expect(wrapper.find('[data-testid="shift-load-error"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('スロットがありません')
  })

  it('AC-E2: 404 は再試行ボタンを出さない', async () => {
    mocks.getSchedule.mockRejectedValue({ statusCode: 404 })
    const wrapper = await mountPage(component)
    expect(wrapper.find('[data-testid="shift-load-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="shift-load-error-retry"]').exists()).toBe(false)
  })

  it('AC-E3: 5xx は再試行ボタンが出て、押すと初回と同じ取得（getSchedule）をやり直す', async () => {
    mocks.getSchedule.mockRejectedValueOnce({ statusCode: 500 })
    const wrapper = await mountPage(component)
    expect(wrapper.find('[data-testid="shift-load-error"]').exists()).toBe(true)

    await wrapper.find('[data-testid="shift-load-error-retry"]').trigger('click')
    await flushPromises()

    expect(mocks.getSchedule).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="shift-load-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="shift-tab-overview"]').exists()).toBe(true)
  })
})

function member(userId: number, displayName: string) {
  return { userId, displayName, avatarUrl: null, roleName: 'MEMBER', joinedAt: '2026-01-01T00:00:00' }
}

function constraint(userId: number | null) {
  return {
    id: userId ?? 1, teamId: 12, userId,
    maxMonthlyHours: null, maxMonthlyDays: 20, maxConsecutiveDays: null,
    maxNightShiftsPerMonth: null, minRestHoursBetweenShifts: null, note: null,
  }
}

async function openAddDialog() {
  const wrapper = await mountPage(ShiftDetailWorkConstraints)
  await wrapper.find('[data-testid="work-constraint-add-member"]').trigger('click')
  await flushPromises()
  await nextTick()
  return wrapper
}

function option(userId: number): HTMLElement | null {
  return document.body.querySelector<HTMLElement>(`[data-testid="work-constraint-member-option-${userId}"]`)
}

describe('勤務制約ページ メンバー選択（AC-N1〜N3 / AC-N5）', () => {
  it('AC-N1: メンバーを追加はチームのメンバー一覧から名前で選ぶ。ID の手入力欄は無い', async () => {
    mocks.getAllMembers.mockResolvedValue({ data: [member(1, '山田花子'), member(2, '佐藤太郎')] })
    await openAddDialog()

    expect(mocks.getAllMembers).toHaveBeenCalledWith('team-a')
    expect(option(1)?.textContent).toContain('山田花子')
    expect(option(2)?.textContent).toContain('佐藤太郎')
    expect(document.body.querySelector('[role="dialog"] .p-inputnumber')).toBeNull()
    expect(document.body.querySelector('[role="dialog"] input[type="number"]')).toBeNull()
    expect(document.body.textContent).not.toContain('ユーザーID')
  })

  it('AC-N2: 個別制約が既にあるメンバーは選択肢に出ない（重複追加できない）', async () => {
    mocks.getAllMembers.mockResolvedValue({ data: [member(1, '山田花子'), member(2, '佐藤太郎')] })
    mocks.listConstraints.mockResolvedValue([constraint(2)])
    await openAddDialog()

    expect(option(1)).not.toBeNull()
    expect(option(2)).toBeNull()
  })

  it('AC-N3: カードは表示名で出て「ユーザー #5」形式を出さない。名前が引けなければ「不明なユーザー」', async () => {
    mocks.getAllMembers.mockResolvedValue({ data: [member(1, '山田花子')] })
    mocks.listConstraints.mockResolvedValue([constraint(1), constraint(5)])
    const wrapper = await mountPage(ShiftDetailWorkConstraints)

    expect(wrapper.find('[data-testid="work-constraint-card-1"]').text()).toContain('山田花子')
    expect(wrapper.find('[data-testid="work-constraint-card-5"]').text()).toContain('Unknown user')
    expect(wrapper.text()).not.toMatch(/ユーザー\s*#\s*\d/)
  })

  it('AC-N5: メンバー一覧の取得件数上限を超えるチームでも全員に到達できる（全件取得 API を使う）', async () => {
    const many = Array.from({ length: 250 }, (_, i) => member(i + 1, `メンバー${i + 1}`))
    mocks.getAllMembers.mockResolvedValue({ data: many })
    await openAddDialog()

    expect(mocks.getMembers).not.toHaveBeenCalled()
    expect(option(250)?.textContent).toContain('メンバー250')

    // 名前検索で絞り込める
    const search = document.body.querySelector<HTMLInputElement>('[data-testid="work-constraint-member-search"]')!
    search.value = 'メンバー250'
    search.dispatchEvent(new Event('input'))
    await nextTick()
    expect(option(250)).not.toBeNull()
    expect(option(1)).toBeNull()
  })

  it('AC-N1: 選んで確定するとそのメンバーのカードが出る', async () => {
    mocks.getAllMembers.mockResolvedValue({ data: [member(1, '山田花子')] })
    const wrapper = await openAddDialog()

    option(1)!.click()
    await nextTick()
    document.body.querySelector<HTMLButtonElement>('[data-testid="work-constraint-add-confirm"]')!.click()
    await flushPromises()

    expect(wrapper.find('[data-testid="work-constraint-card-1"]').exists()).toBe(true)
  })
})
