import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ShiftRequestPage from './shift-request.vue'

const mocks = vi.hoisted(() => ({
  listSchedules: vi.fn(),
  fetchMyTeamsWithResult: vi.fn(),
}))
mockNuxtImport('useShiftApi', () => () => ({ listSchedules: mocks.listSchedules }))
mockNuxtImport('useShiftSlotApi', () => () => ({ listSlots: vi.fn().mockResolvedValue([]) }))
mockNuxtImport('useShiftRequestApi', () => () => ({
  listMyRequests: vi.fn().mockResolvedValue([]),
  submitRequest: vi.fn(),
  updateRequest: vi.fn(),
}))
mockNuxtImport('useShiftAvailabilityDefaultApi', () => () => ({
  getAvailabilityDefaults: vi.fn().mockResolvedValue([]),
}))
mockNuxtImport('useTeamStore', () => () => ({
  myTeams: [{ id: 1, name: 'チームA', iconUrl: null, nickname1: null }],
  fetchMyTeamsWithResult: mocks.fetchMyTeamsWithResult,
}))
mockNuxtImport('useNotification', () => () => ({ error: vi.fn(), success: vi.fn() }))

beforeEach(() => {
  vi.clearAllMocks()
  mocks.fetchMyTeamsWithResult.mockResolvedValue({ ok: true })
})

describe('シフト希望提出ページ: シフト表選択', () => {
  it('締切・期間を ISO 文字列のまま（T / Z 付き）で表示しない', async () => {
    mocks.listSchedules.mockResolvedValue([{
      id: 5, teamId: 1,
      content: { title: '12月シフト', periodType: null, note: null },
      period: { startDate: '2026-12-01', endDate: '2026-12-31', requestDeadline: '2099-11-25T14:59:59Z' },
      status: { status: 'COLLECTING', publishedAt: null, publishedBy: null },
      audit: { createdBy: 1, createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z' },
    }])
    const wrapper = await mountSuspended(ShiftRequestPage)
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('12月シフト')
    expect(text).toMatch(/2099/)
    expect(text).not.toMatch(/\d{4}-\d{2}-\d{2}T/)
    expect(text).not.toMatch(/\d{2}Z/)
    expect(text).not.toContain('2026-12-01')
  })
})
