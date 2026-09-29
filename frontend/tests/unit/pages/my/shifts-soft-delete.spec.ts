import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import MyShiftsPage from '~/pages/my/shifts.vue'
import type { ShiftRequestResponse } from '~/types/shift'

const getMyShiftRequests = vi.fn()
const showError = vi.fn()

mockNuxtImport('useShiftApi', () => () => ({ getMyShiftRequests }))
mockNuxtImport('useNotification', () => () => ({ showError }))

function request(overrides: Partial<ShiftRequestResponse> = {}): ShiftRequestResponse {
  return {
    id: 701,
    scheduleId: 91,
    userId: 23,
    slotId: 301,
    slotDate: '2026-10-10',
    preference: 'AVAILABLE',
    note: null,
    submittedAt: '2026-09-29T12:00:00',
    scheduleDeleted: false,
    ...overrides,
  }
}

async function mountPage(rows: ShiftRequestResponse[]) {
  getMyShiftRequests.mockResolvedValue(rows)
  const wrapper = await mountSuspended(MyShiftsPage)
  for (let i = 0; i < 5; i++) await Promise.resolve()
  return wrapper
}

beforeEach(() => {
  getMyShiftRequests.mockReset()
  showError.mockReset()
})

describe('自分のシフト希望履歴 — 親シフト表の削除', () => {
  it('削除済みの親を明示し、存在しない詳細へのリンクを表示しない', async () => {
    const wrapper = await mountPage([request({ scheduleDeleted: true })])

    expect(wrapper.get('[data-testid="my-shift-schedule-deleted-701"]').text()).toContain(
      'シフト表は削除済みです',
    )
    expect(wrapper.find('a[href="/shift/91"]').exists()).toBe(false)
  })

  it('有効な親の希望には従来どおり詳細リンクを表示する', async () => {
    const wrapper = await mountPage([request()])

    expect(wrapper.find('[data-testid="my-shift-schedule-deleted-701"]').exists()).toBe(false)
    expect(wrapper.find('a[href="/shift/91"]').exists()).toBe(true)
  })
})
