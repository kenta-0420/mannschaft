import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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

describe('CMP-260910-1609 AC7: 希望受付の案内と入力導線', () => {
  beforeEach(() => {
    // Dateだけを差し替え、mountListのsetTimeoutやNuxtの非同期描画は止めない。
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-01T03:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  const cases: Array<{
    name: string
    status: ShiftScheduleResponse['status']['status']
    deadline: string | null
    accepting: boolean
  }> = [
    { name: 'DRAFT/nullは未開始で終了案内を出さない', status: 'DRAFT', deadline: null, accepting: false },
    { name: 'COLLECTING/nullは期限未設定として受付可能', status: 'COLLECTING', deadline: null, accepting: true },
    { name: 'COLLECTING/1秒後の締切は受付可能', status: 'COLLECTING', deadline: '2026-10-01T12:00:01+09:00', accepting: true },
    // 既存isAcceptingShiftRequestsのisBefore契約を保持する。同時刻は受付不可。
    { name: 'COLLECTING/締切と同時刻は終了', status: 'COLLECTING', deadline: '2026-10-01T12:00:00+09:00', accepting: false },
    { name: 'COLLECTING/1秒前の締切は終了', status: 'COLLECTING', deadline: '2026-10-01T11:59:59+09:00', accepting: false },
    { name: 'ADJUSTING/nullは受付終了', status: 'ADJUSTING', deadline: null, accepting: false },
    { name: 'PUBLISHED/nullは受付終了', status: 'PUBLISHED', deadline: null, accepting: false },
    { name: 'ARCHIVED/nullは受付終了', status: 'ARCHIVED', deadline: null, accepting: false },
  ]

  it.each(cases)('$name', async ({ status, deadline, accepting }) => {
    const target = schedule(status)
    target.period.requestDeadline = deadline
    const wrapper = await mountList(true, [target])
    const text = wrapper.text()
    const bulkInput = wrapper.findAll('button').filter(button => button.find('.pi-list-check').exists())

    if (status === 'DRAFT') {
      // 未存在の翻訳キーを型参照せず、利用者へ見える未開始案内を検査する。
      expect(text).not.toContain('希望の受付は終了しました')
      expect(text).toMatch(/受付.*(?:開始前|まだ開始|未開始)/)
    }
    else if (accepting) {
      expect(text).not.toContain('希望の受付は終了しました')
    }
    else {
      expect(text).toContain('希望の受付は終了しました')
    }
    expect(bulkInput).toHaveLength(accepting ? 1 : 0)

    // 案内の見た目だけでなく、実際に希望ダイアログを開くイベントも照合する。
    await wrapper.get('.space-y-2 > div').trigger('click')
    expect(wrapper.emitted('select')).toEqual(accepting ? [[399]] : undefined)
  })
})
