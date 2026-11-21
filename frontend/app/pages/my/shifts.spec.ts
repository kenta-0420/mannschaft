import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import MyShiftsPage from './shifts.vue'

const { getMyShiftRequests, showError } = vi.hoisted(() => ({
  getMyShiftRequests: vi.fn(),
  showError: vi.fn(),
}))
mockNuxtImport('useShiftApi', () => () => ({ getMyShiftRequests }))
mockNuxtImport('useNotification', () => () => ({ showError }))

function row(id: number, preference: string) {
  return {
    id, scheduleId: 10, userId: 1, slotId: id, slotDate: '2026-11-0' + id,
    preference, note: null, submittedAt: '2026-10-30T09:00:00+09:00',
  }
}

beforeEach(() => {
  getMyShiftRequests.mockReset()
  showError.mockReset()
})

describe('自分のシフト希望一覧ページ', () => {
  it('希望の enum 値（WEAK_REST 等）を生表示せず、希望強度のラベルで表示する', async () => {
    getMyShiftRequests.mockResolvedValue([
      row(1, 'PREFERRED'), row(2, 'AVAILABLE'), row(3, 'WEAK_REST'),
      row(4, 'STRONG_REST'), row(5, 'ABSOLUTE_REST'),
    ])
    const wrapper = await mountSuspended(MyShiftsPage)
    await flushPromises()

    const text = wrapper.text()
    expect(text).not.toMatch(/PREFERRED|AVAILABLE|WEAK_REST|STRONG_REST|ABSOLUTE_REST/)
    // テスト環境ロケール（en）の shift.preference.* ラベル
    for (const label of ['Work preferred', 'No preference', 'Can work if needed', 'Prefer day off', 'Day off required']) {
      expect(text).toContain(label)
    }
  })
})
