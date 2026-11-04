import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
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

interface TestI18n {
  locale: { value: string }
  setLocale: (l: string) => Promise<void>
  t: (key: string) => string
}
const i18n = () => (useNuxtApp() as unknown as { $i18n: TestI18n }).$i18n
const activeT = (key: string) => i18n().t(key)

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

beforeEach(async () => {
  await i18n().setLocale('en') // ロケール別テストの切替が他テストへ漏れないよう既定に戻す
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
    // disabled は PrimeVue Button の props ではなく DOM 属性として出る（実物に合わせて属性で検証）
    expect(send.attributes('disabled')).toBeDefined()
    const cancel = buttons.find(b => b.props('text') === true)!
    expect(cancel.attributes('disabled')).toBeDefined()
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
        data: { error: { code: 'RESERVATION_055', message: 'x' } },
      })
    })
    const { wrapper, vm } = await mountForm()
    vm.openConfirm()
    await vm.confirmSend()
    expect(mockError).toHaveBeenCalledTimes(1)
    // テスト環境の既定ロケールに依存しないよう、有効なロケールの辞書値から期待値を引く
    expect(String(mockError.mock.calls[0]![0])).toBe(activeT('emergency_closure.error.idempotency_conflict'))
    expect(activeT('emergency_closure.error.idempotency_conflict')).not.toBe('emergency_closure.error.idempotency_conflict')
    wrapper.unmount()
  }, 60000)

  it('0334-13: 別コードの 409 以外のエラーは汎用の送信失敗文言のまま', async () => {
    defaultImpl(async () => {
      throw Object.assign(new Error('boom'), { statusCode: 500, data: { error: { code: 'RESERVATION_999', message: 'x' } } })
    })
    const { wrapper, vm } = await mountForm()
    vm.openConfirm()
    await vm.confirmSend()
    expect(String(mockError.mock.calls[0]![0])).toBe(activeT('emergency_closure.error.send_failed'))
    expect(String(mockError.mock.calls[0]![0])).not.toBe(activeT('emergency_closure.error.idempotency_conflict'))
    wrapper.unmount()
  }, 60000)

  it.each(['ja', 'en', 'zh', 'ko', 'es', 'de'] as const)('0334-13: ロケール %s で専用文言が出る（日本語以外では日本語でない）', async (locale) => {
    // 生 JSON の文字列を直接読む（i18n 経由の import はコンパイル済み AST になり文字列でない）
    const raw = readFileSync(resolve(process.cwd(), 'app/locales', locale, 'reservation.json'), 'utf-8')
    const messages = JSON.parse(raw.charCodeAt(0) === 0xFEFF ? raw.slice(1) : raw) as { emergency_closure: { error: { idempotency_conflict?: string } } }
    const expected = messages.emergency_closure.error.idempotency_conflict
    expect(expected).toBeTruthy()
    if (locale !== 'ja') expect(expected).not.toMatch(/[぀-ヿ]/) // かな残存なし
    defaultImpl(async () => {
      throw Object.assign(new Error('conflict'), { statusCode: 409, data: { error: { code: 'RESERVATION_055', message: 'x' } } })
    })
    const { wrapper, vm } = await mountForm()
    await i18n().setLocale(locale)
    expect(i18n().locale.value).toBe(locale) // 切替が実際に効いていること
    vm.openConfirm()
    await vm.confirmSend()
    expect(String(mockError.mock.calls[0]![0])).toBe(expected)
    wrapper.unmount()
  }, 120000)

  it('0334-1: 成功後に履歴再取得が1回走り、履歴が +1 される', async () => {
    const row = (id: number) => ({ id, teamId: 1, startDate: '2026-11-05', endDate: '2026-11-05', startTime: null, endTime: null, reason: 'r', subject: 's', sentCount: 1, createdAt: '2026-11-05T10:00:00' })
    let rows = [row(1)]
    mockApi.mockImplementation(async (url: string, opts?: PostCall) => {
      if (url.endsWith('/emergency-closures') && opts?.method === 'POST') {
        rows = [row(2), ...rows]
        return { data: { sentCount: 1 } }
      }
      if (url.endsWith('/emergency-closures')) return { data: rows }
      return { data: [] }
    })
    const { wrapper, vm } = await mountForm()
    const listCalls = () => mockApi.mock.calls.filter(c => (c[1] as PostCall | undefined)?.method !== 'POST' && String(c[0]).endsWith('/emergency-closures')).length
    const before = listCalls()
    vm.openConfirm()
    await Promise.all([vm.confirmSend(), vm.confirmSend(), vm.confirmSend()])
    expect(listCalls()).toBe(before + 1)
    expect((vm as unknown as { historyItems: unknown[] }).historyItems).toHaveLength(2)
    wrapper.unmount()
  }, 60000)
})
