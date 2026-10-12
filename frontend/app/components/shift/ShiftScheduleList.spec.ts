import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ShiftScheduleList from './ShiftScheduleList.vue'
import type { ShiftScheduleResponse } from '~/types/shift'

const mockListSchedules = vi.fn()
const mockRemindUnsubmitted = vi.fn()
const mockNotifySuccess = vi.fn()
const mockNotifyInfo = vi.fn()
const mockNotifyError = vi.fn()
let confirmAcceptCallback: (() => void | Promise<void>) | null = null

mockNuxtImport('useShiftApi', () => () => ({
  listSchedules: mockListSchedules,
  remindUnsubmitted: mockRemindUnsubmitted,
}))
mockNuxtImport('useNotification', () => () => ({
  success: mockNotifySuccess,
  info: mockNotifyInfo,
  error: mockNotifyError,
}))
mockNuxtImport('useConfirm', () => () => ({
  require: (options: { accept: () => void | Promise<void> }) => {
    confirmAcceptCallback = options.accept
  },
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))
const mockNavigateTo = vi.hoisted(() => vi.fn())
mockNuxtImport('navigateTo', () => mockNavigateTo)

function schedule(status = 'COLLECTING'): ShiftScheduleResponse {
  return {
    id: 399,
    teamId: 12,
    content: { title: '10月第1週', periodType: 'WEEKLY', note: null },
    period: { startDate: '2026-10-01', endDate: '2026-10-07', requestDeadline: null },
    status: { status: status as ShiftScheduleResponse['status']['status'], publishedAt: null, publishedBy: null },
    audit: { createdBy: 1, createdAt: '', updatedAt: '' },
  }
}

async function mountList(canManage: boolean, schedules = [schedule()]) {
  mockListSchedules.mockResolvedValue(schedules)
  mockRemindUnsubmitted.mockResolvedValue({
    scheduleId: 399,
    remindedCount: 2,
    remindedUserIds: [23, 24],
  })
  const wrapper = await mountSuspended(ShiftScheduleList, {
    props: { teamId: 12, teamSlug: 'team-12', canManage, canManageBoard: canManage },
  })
  await new Promise(resolve => setTimeout(resolve, 0))
  return wrapper
}

beforeEach(() => {
  mockListSchedules.mockReset()
  mockRemindUnsubmitted.mockReset()
  mockNotifySuccess.mockReset()
  mockNotifyInfo.mockReset()
  mockNotifyError.mockReset()
  confirmAcceptCallback = null
})

async function mountListWith(
  props: { canManage: boolean; canManageBoard: boolean; canRequestChange: boolean },
  schedules = [schedule()],
) {
  mockListSchedules.mockResolvedValue(schedules)
  const wrapper = await mountSuspended(ShiftScheduleList, {
    props: { teamId: 12, teamSlug: 'team-12', ...props },
  })
  await new Promise(resolve => setTimeout(resolve, 0))
  return wrapper
}

describe('ShiftScheduleList 変更依頼ボタン（CMP-261010-1432 AC-J1 / J2 / J4）', () => {
  it('AC-J1: 変更依頼を出せる利用者は行の「変更依頼」ボタンから change-requests へ遷移する', async () => {
    const wrapper = await mountListWith({ canManage: false, canManageBoard: false, canRequestChange: true })
    const button = wrapper.find('[data-testid="shift-change-request-399"]')
    expect(button.exists()).toBe(true)
    await button.trigger('click')
    expect(mockNavigateTo).toHaveBeenCalledWith('/teams/team-12/shifts/399/change-requests')
  })

  it('AC-J1: 変更依頼を出せない利用者（SUPPORTER 等）には行の変更依頼ボタンを出さない', async () => {
    const wrapper = await mountListWith({ canManage: false, canManageBoard: false, canRequestChange: false })
    expect(wrapper.find('[data-testid="shift-change-request-399"]').exists()).toBe(false)
  })

  it('AC-J2: ボードへのボタンは canManageBoard のときだけ（一般メンバーには変更依頼だけ）', async () => {
    const member = await mountListWith({ canManage: false, canManageBoard: false, canRequestChange: true })
    expect(member.find('[data-testid="shift-board-399"]').exists()).toBe(false)
    expect(member.find('[data-testid="shift-change-request-399"]').exists()).toBe(true)
    member.unmount()

    const admin = await mountListWith({ canManage: true, canManageBoard: true, canRequestChange: true })
    expect(admin.find('[data-testid="shift-board-399"]').exists()).toBe(true)
  })

  it('AC-J4: シフト表が0件のときは空状態だけを描画し、変更依頼ボタンを出さない', async () => {
    const wrapper = await mountListWith({ canManage: false, canManageBoard: false, canRequestChange: true }, [])
    expect(wrapper.find('[data-testid="shift-change-request-399"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Change request')
  })
})

describe('ShiftScheduleList 手動リマインド', () => {
  it('管理者に受付中の操作を表示し、確認後に送信件数を表示する', async () => {
    const wrapper = await mountList(true)

    const button = wrapper.find('[data-testid="shift-reminder-399"]')
    expect(button.exists()).toBe(true)
    await button.trigger('click')
    expect(mockRemindUnsubmitted).not.toHaveBeenCalled()
    expect(confirmAcceptCallback).toBeTypeOf('function')

    await confirmAcceptCallback?.()

    expect(mockRemindUnsubmitted).toHaveBeenCalledWith(399)
    expect(mockNotifySuccess).toHaveBeenCalledWith(
      expect.any(String),
      expect.stringContaining('2'),
    )
  })

  it('対象が0人の場合は送信件数と区別して表示する', async () => {
    const wrapper = await mountList(true)
    await wrapper.find('[data-testid="shift-reminder-399"]').trigger('click')
    mockRemindUnsubmitted.mockResolvedValue({ scheduleId: 399, remindedCount: 0, remindedUserIds: [] })
    await confirmAcceptCallback?.()

    expect(mockNotifyInfo).toHaveBeenCalledTimes(1)
    expect(mockNotifySuccess).not.toHaveBeenCalled()
  })

  it('API失敗を送信失敗として表示する', async () => {
    const wrapper = await mountList(true)
    await wrapper.find('[data-testid="shift-reminder-399"]').trigger('click')
    mockRemindUnsubmitted.mockRejectedValue(new Error('request failed'))
    await confirmAcceptCallback?.()

    expect(mockNotifyError).toHaveBeenCalledTimes(1)
    expect(mockNotifySuccess).not.toHaveBeenCalled()
  })

  it('管理権限のない利用者と受付終了のシフトには督促操作を表示しない', async () => {
    const memberView = await mountList(false)
    expect(memberView.find('[data-testid="shift-reminder-399"]').exists()).toBe(false)
    memberView.unmount()

    const closedView = await mountList(true, [schedule('ADJUSTING')])
    expect(closedView.find('[data-testid="shift-reminder-399"]').exists()).toBe(false)
  })
})
