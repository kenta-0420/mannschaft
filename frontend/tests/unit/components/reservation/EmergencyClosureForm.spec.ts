import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import EmergencyClosureForm from '~/components/reservation/EmergencyClosureForm.vue'

/**
 * EmergencyClosureForm.vue 緊急休業の一括送信 ユニットテスト（CMP-261028-2329）。
 *
 * 観点:
 *   AC-1: BE の実応答 `{ data: { sentCount: 3, ... } }` で送信すると、完了トースト・結果表示に「3」が出る
 *   AC-2: 件数に undefined が混入しない（BE は sentCount を返す。notifiedCount は存在しない）
 *
 * useApi を BE の実際の応答形でモックし、composable（useEmergencyClosureApi）を含めた経路で検証する。
 */
const mockApi = vi.fn()
mockNuxtImport('useApi', () => () => mockApi)

const mockNotifySuccess = vi.fn()
mockNuxtImport('useNotification', () => () => ({
  success: mockNotifySuccess,
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
}))

/** BE の EmergencyClosureResponse をそのまま JSON 化した形（notifiedCount ではなく sentCount）。 */
const closureResponse = {
  id: 10,
  teamId: 1,
  startDate: '2026-11-01',
  endDate: '2026-11-01',
  startTime: null,
  endTime: null,
  reason: '設備点検',
  subject: '臨時休業のお知らせ',
  messageBody: '本文',
  sentCount: 3,
  cancelReservations: false,
  createdBy: 1,
  createdAt: '2026-10-29T10:00:00',
}

interface FormVm {
  confirmSend: () => Promise<void>
  sendResult: number | null
}

beforeEach(() => {
  mockApi.mockReset()
  mockNotifySuccess.mockReset()
  mockApi.mockImplementation(async (url: string, opts?: { method?: string }) => {
    if (url.endsWith('/emergency-closures') && opts?.method === 'POST') {
      return { data: closureResponse }
    }
    if (url.endsWith('/emergency-closures')) {
      return { data: [closureResponse] }
    }
    return { data: [] }
  })
})

describe('EmergencyClosureForm 一括送信の件数表示', () => {
  it('AC-1/2: sentCount=3 の応答で完了トーストと結果表示に 3 が出て undefined を含まない', async () => {
    const wrapper = await mountSuspended(EmergencyClosureForm, { props: { teamId: 'team-slug' } })
    const vm = wrapper.vm as unknown as FormVm

    await vm.confirmSend()
    await wrapper.vm.$nextTick()

    expect(mockNotifySuccess).toHaveBeenCalledTimes(1)
    const message = String(mockNotifySuccess.mock.calls[0]![0])
    expect(message).toContain('3')
    expect(message).not.toContain('undefined')
    expect(vm.sendResult).toBe(3)
    expect(wrapper.text()).not.toContain('undefined')
    wrapper.unmount()
  }, 60000)
})