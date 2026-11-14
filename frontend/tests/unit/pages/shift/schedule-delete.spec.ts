import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ShiftDetailPage from '~/pages/shift/[id]/index.vue'
import type { ShiftScheduleResponse } from '~/types/shift'

const getSchedule = vi.fn()
const updateSchedule = vi.fn()
const transitionStatus = vi.fn()
const deleteSchedule = vi.fn()
const listSlots = vi.fn()
const navigateTo = vi.fn()
const notifySuccess = vi.fn()
const handleApiError = vi.fn()
let role = 'ADMIN'
let acceptDelete: (() => void | Promise<void>) | null = null

const teamStore = {
  get myTeams() {
    return [{ id: 1, role }]
  },
  fetchMyTeamsWithResult: vi.fn(async () => ({ ok: true as const })),
}

mockNuxtImport('useRoute', () => () => ({ params: { id: '91' } }))
mockNuxtImport('useTeamStore', () => () => teamStore)
mockNuxtImport('useShiftApi', () => () => ({
  getSchedule,
  updateSchedule,
  transitionStatus,
  deleteSchedule,
}))
mockNuxtImport('useShiftSlotApi', () => () => ({ listSlots }))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))
mockNuxtImport('useNotification', () => () => ({ success: notifySuccess }))
mockNuxtImport('useConfirm', () => () => ({
  require: (options: { accept: () => void | Promise<void> }) => {
    acceptDelete = options.accept
  },
}))
mockNuxtImport('navigateTo', () => (...args: unknown[]) => navigateTo(...args))

const schedule: ShiftScheduleResponse = {
  id: 91,
  teamId: 1,
  content: { title: '削除対象シフト', periodType: 'WEEKLY', note: null },
  period: { startDate: '2026-10-10', endDate: '2026-10-11', requestDeadline: null },
  status: { status: 'COLLECTING', publishedAt: null, publishedBy: null },
  audit: { createdBy: 3, createdAt: '2026-09-29T00:00:00', updatedAt: '2026-09-29T00:00:00' },
}

async function mountPage() {
  getSchedule.mockResolvedValue(schedule)
  listSlots.mockResolvedValue([])
  const wrapper = await mountSuspended(ShiftDetailPage)
  for (let i = 0; i < 5; i++) await Promise.resolve()
  return wrapper
}

beforeEach(() => {
  role = 'ADMIN'
  acceptDelete = null
  getSchedule.mockReset()
  updateSchedule.mockReset()
  transitionStatus.mockReset()
  deleteSchedule.mockReset()
  listSlots.mockReset()
  navigateTo.mockReset()
  notifySuccess.mockReset()
  handleApiError.mockReset()
  teamStore.fetchMyTeamsWithResult.mockClear()
})

describe('シフト表詳細 — 削除導線', () => {
  it('ADMINは確認後に削除し、一覧へ戻る', async () => {
    deleteSchedule.mockResolvedValue(undefined)
    const wrapper = await mountPage()

    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('削除対象シフト')
    const button = wrapper.get('[data-testid="shift-schedule-delete"]')
    await button.trigger('click')
    expect(deleteSchedule).not.toHaveBeenCalled()
    expect(acceptDelete).toBeTypeOf('function')

    await acceptDelete?.()

    expect(deleteSchedule).toHaveBeenCalledWith(91)
    expect(notifySuccess).toHaveBeenCalled()
    expect(navigateTo).toHaveBeenCalledWith('/shift')
  })

  it('MEMBERには削除導線を表示しない', async () => {
    role = 'MEMBER'
    const wrapper = await mountPage()

    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('削除対象シフト')
    expect(wrapper.find('[data-testid="shift-schedule-delete"]').exists()).toBe(false)
  })
})
