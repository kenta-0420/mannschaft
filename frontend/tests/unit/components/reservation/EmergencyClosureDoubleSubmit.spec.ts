import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import EmergencyClosureForm from '~/components/reservation/EmergencyClosureForm.vue'
import EmergencyClosureConfirmDialog from '~/components/reservation/emergency-closure/EmergencyClosureConfirmDialog.vue'

/**
 * 緊急休業の一括送信 多重送信防止（CMP-261104-0334 / P1・FE 部分）。
 *   0334-1 同期3連打で API 1回・成功トースト1回
 *   0334-2 送信中は確認ダイアログの送信ボタン disabled/loading、閉じられない
 *   0334-3 失敗後に再押下可・同内容は同キー・内容変更/成功後は新キー
 *   0334-13 内容相違 409 で専用文言
 */
const mockApi = vi.fn()
mockNuxtImport('useApi', () => () => mockApi)

const mockSuccess = vi.fn()
const mockError = vi.fn()
mockNuxtImport('useNotification', () => () => ({
  success: mockSuccess, info: vi.fn(), warn: vi.fn(), error: mockError,
}))

interface FormVm {
  confirmSend: () => Promise<void>
  openConfirm: () => void
  sendLoading: boolean
  showConfirm: boolean
  reason: string
  subject: string
  messageBody: string
}

type PostCall = { method?: string, headers?: Record<string, string> }
const postCalls = () => mockApi.mock.calls.filter(c => (c[1] as PostCall | undefined)?.method === 'POST')
const keyOf = (i: number) => (postCalls()[i]![1] as PostCall).headers!['Idempotency-Key']

let release: (() => void) | null = null
function defaultImpl(postImpl: () => Promise<unknown>) {
  mockApi.mockImplementation(async (url: string, opts?: PostCall) => {
    if (url.endsWith('/emergency-closures') && opts?.method === 'POST') return postImpl()
    return { data: [] }
  })
}

async function mountForm() {
  const wrapper = await mountSuspended(EmergencyClosureForm, { props: { teamId: 'team-slug' } })
  const vm = wrapper.vm as unknown as FormVm
  vm.reason = '設備点検'
  vm.subject = '件名'
  vm.messageBody = '本文'
  return { wrapper, vm }
}

beforeEach(() => {
  mockApi.mockReset()
  mockSuccess.mockReset()
  mockError.mockReset()
  release = null
})

describe('緊急休業 多重送信防止', () => {
  it('0334-1: 応答保留中に同期3連打しても API 1回・成功トースト1回', async () => {
    defaultImpl(() => new Promise((resolve) => { release = () => resolve({ data: { sentCount: 2 } }) }))
    const { wrapper, vm } = await mountForm()
    vm.openConfirm()
    const p = [vm.confirmSend(), vm.confirmSend(), vm.confirmSend()]
    expect(postCalls()).toHaveLength(1)
    release!()
    await Promise.all(p)
    expect(postCalls()).toHaveLength(1)
    expect(mockSuccess).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  }, 60000)

  it('0334-2: 送信中はダイアログが開いたまま sending=true、完了後に閉じる', async () => {
    defaultImpl(() => new Promise((resolve) => { release = () => resolve({ data: { sentCount: 2 } }) }))
    const { wrapper, vm } = await mountForm()
    vm.openConfirm()
    const p = vm.confirmSend()
    await wrapper.vm.$nextTick()
    expect(vm.showConfirm).toBe(true)
    const dialog = wrapper.findComponent(EmergencyClosureConfirmDialog)
    expect(dialog.props('sending')).toBe(true)
    release!()
    await p
    expect(vm.showConfirm).toBe(false)
    wrapper.unmount()
  }, 60000)

  it('0334-2: ConfirmDialog は sending 中に closable/Esc/マスク閉じ無効、送信・キャンセルボタン disabled', async () => {
    const wrapper = await mountSuspended(EmergencyClosureConfirmDialog, {
      props: { visible: true, periodText: 'p', subject: 's', reason: 'r', cancelReservations: false, sending: true },
    })
    const dialog = wrapper.findComponent({ name: 'Dialog' })
    expect(dialog.props('closable')).toBe(false)
    expect(dialog.props('closeOnEscape')).toBe(false)
    expect(dialog.props('dismissableMask')).toBe(false)
    const buttons = wrapper.findAllComponents({ name: 'Button' })
    const send = buttons.find(b => b.props('icon') === 'pi pi-send')!
    expect(send.props('loading')).toBe(true)
    expect(send.props('disabled')).toBe(true)
    const cancel = buttons.find(b => b.props('text') === true)!
    expect(cancel.props('disabled')).toBe(true)
    wrapper.unmount()
  }, 60000)

  it('0334-3: 失敗後に再送でき、同内容は同キー・成功後は新キー', async () => {
    let n = 0
    defaultImpl(async () => {
      n++
      if (n === 1) throw new Error('network')
      return { data: { sentCount: 1 } }
    })
    const { wrapper, vm } = await mountForm()
    vm.openConfirm()
    await vm.confirmSend()
    expect(mockError).toHaveBeenCalledTimes(1)
    expect(vm.sendLoading).toBe(false)
    vm.openConfirm()
    await vm.confirmSend()
    expect(postCalls()).toHaveLength(2)
    expect(keyOf(1)).toBe(keyOf(0))
    vm.openConfirm()
    await vm.confirmSend()
    expect(keyOf(2)).not.toBe(keyOf(1))
    wrapper.unmount()
  }, 60000)

  it('0334-3: 失敗後に内容を変えて再送すると新キー', async () => {
    let n = 0
    defaultImpl(async () => {
      n++
      if (n === 1) throw new Error('network')
      return { data: { sentCount: 1 } }
    })
    const { wrapper, vm } = await mountForm()
    vm.openConfirm()
    await vm.confirmSend()
    vm.reason = '別の理由'
    vm.openConfirm()
    await vm.confirmSend()
    expect(keyOf(1)).not.toBe(keyOf(0))
    wrapper.unmount()
  }, 60000)

  it('0334-13: 内容相違の 409 は専用文言を表示する', async () => {
    defaultImpl(async () => {
      throw Object.assign(new Error('conflict'), {
        statusCode: 409,
        data: { error: { code: 'EMERGENCY_CLOSURE_IDEMPOTENCY_CONFLICT', message: 'x' } },
      })
    })
    const { wrapper, vm } = await mountForm()
    vm.openConfirm()
    await vm.confirmSend()
    expect(mockError).toHaveBeenCalledTimes(1)
    expect(String(mockError.mock.calls[0]![0])).toBe('同じ送信操作で内容が異なっています。画面を確認し、送信内容を見直してもう一度お試しください')
    wrapper.unmount()
  }, 60000)
})
