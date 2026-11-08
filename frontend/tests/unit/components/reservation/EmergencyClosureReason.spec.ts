import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import EmergencyClosureForm from '~/components/reservation/EmergencyClosureForm.vue'
import EmergencyClosureConfirmDialog from '~/components/reservation/emergency-closure/EmergencyClosureConfirmDialog.vue'
import EmergencyClosureHistory from '~/components/reservation/emergency-closure/EmergencyClosureHistory.vue'

/**
 * 緊急休業 休業理由（reason）の入力・検証・失敗表示・履歴取得失敗の分離（CMP-261030-0649）。
 *
 * 観点:
 *   AC-1 理由入力欄(maxlength200)とテンプレート自動入力 / AC-2 カスタム・手入力でも reason 送信
 *   AC-3 理由空は警告のみ / AC-4 200文字境界 / AC-5 確認ダイアログに理由
 *   AC-6a フィールドエラー表示 / AC-6b send_failed / AC-6c 失敗後の保持
 *   AC-7 POST 成功後の履歴取得失敗を握りつぶさない
 */
const mockApi = vi.fn()
mockNuxtImport('useApi', () => () => mockApi)

const mockNotifySuccess = vi.fn()
const mockNotifyWarn = vi.fn()
const mockNotifyError = vi.fn()
mockNuxtImport('useNotification', () => () => ({
  success: mockNotifySuccess,
  info: vi.fn(),
  warn: mockNotifyWarn,
  error: mockNotifyError,
}))

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
  openConfirm: () => void
  confirmSend: () => Promise<void>
  applyTemplate: (i: number) => void
  showConfirm: boolean
  sendLoading: boolean
  sendResult: number | null
}

type MockOpts = { method?: string, body?: Record<string, unknown> }

/** テスト環境のロケールに依存しないよう、実 i18n で文言を解決して比較する。 */
function tr(key: string, params?: Record<string, unknown>): string {
  return String(useNuxtApp().$i18n.t(key, params ?? {}))
}

function postCalls(): Array<[string, MockOpts]> {
  return mockApi.mock.calls.filter(c => (c[1] as MockOpts | undefined)?.method === 'POST') as Array<[string, MockOpts]>
}

async function fill(wrapper: Awaited<ReturnType<typeof mountSuspended>>, v: { reason?: string, subject?: string, body?: string }) {
  if (v.reason !== undefined) await wrapper.find('[data-testid="closure-reason"]').setValue(v.reason)
  if (v.subject !== undefined) await wrapper.find('[data-testid="closure-subject"]').setValue(v.subject)
  if (v.body !== undefined) await wrapper.find('[data-testid="closure-body"]').setValue(v.body)
}

beforeEach(() => {
  mockApi.mockReset()
  mockNotifySuccess.mockReset()
  mockNotifyWarn.mockReset()
  mockNotifyError.mockReset()
  mockApi.mockImplementation(async (url: string, opts?: MockOpts) => {
    if (url.endsWith('/emergency-closures') && opts?.method === 'POST') return { data: closureResponse }
    if (url.endsWith('/emergency-closures')) return { data: [closureResponse] }
    return { data: [] }
  })
})

describe('EmergencyClosureForm 休業理由 (CMP-261030-0649)', () => {
  it('AC-1: 理由の入力欄があり maxlength=200。テンプレート選択で自動入力され、編集できる', async () => {
    const wrapper = await mountSuspended(EmergencyClosureForm, { props: { teamId: 'team-slug' } })
    const input = wrapper.find('[data-testid="closure-reason"]')
    expect(input.exists()).toBe(true)
    expect(input.attributes('maxlength')).toBe('200')
    ;(wrapper.vm as unknown as FormVm).applyTemplate(0)
    await wrapper.vm.$nextTick()
    const filled = (input.element as HTMLInputElement).value
    expect(filled.length).toBeGreaterThan(0)
    await input.setValue(`${filled}（編集）`)
    expect((input.element as HTMLInputElement).value).toBe(`${filled}（編集）`)
    wrapper.unmount()
  }, 60000)

  it('AC-2: カスタム選択後に理由を手入力すれば reason が送られ、成功トーストに件数が出る', async () => {
    const wrapper = await mountSuspended(EmergencyClosureForm, { props: { teamId: 'team-slug' } })
    const vm = wrapper.vm as unknown as FormVm
    vm.applyTemplate(3)
    await fill(wrapper, { reason: '手入力の理由', subject: '件名', body: '本文' })
    vm.openConfirm()
    expect(vm.showConfirm).toBe(true)
    await vm.confirmSend()
    const posts = postCalls()
    expect(posts).toHaveLength(1)
    expect(posts[0]![1].body).toMatchObject({ reason: '手入力の理由' })
    expect(String(mockNotifySuccess.mock.calls[0]![0])).toContain('3')
    wrapper.unmount()
  }, 60000)

  it('AC-2: テンプレート未選択の手入力でも reason が送られる', async () => {
    const wrapper = await mountSuspended(EmergencyClosureForm, { props: { teamId: 'team-slug' } })
    const vm = wrapper.vm as unknown as FormVm
    await fill(wrapper, { reason: '理由A', subject: '件名', body: '本文' })
    vm.openConfirm()
    await vm.confirmSend()
    expect(postCalls()).toHaveLength(1)
    expect(postCalls()[0]![1].body).toMatchObject({ reason: '理由A' })
    wrapper.unmount()
  }, 60000)

  it.each(['', '   ', '　'])('AC-3: 理由が %j なら確認ダイアログを開かず警告し API を呼ばない', async (reason) => {
    const wrapper = await mountSuspended(EmergencyClosureForm, { props: { teamId: 'team-slug' } })
    const vm = wrapper.vm as unknown as FormVm
    await fill(wrapper, { reason, subject: '件名', body: '本文' })
    vm.openConfirm()
    expect(vm.showConfirm).toBe(false)
    expect(mockNotifyWarn).toHaveBeenCalledTimes(1)
    expect(String(mockNotifyWarn.mock.calls[0]![0])).toContain(tr('emergency_closure.error.reason_required'))
    expect(postCalls()).toHaveLength(0)
    wrapper.unmount()
  }, 60000)

  it('AC-4: reason・subject は 200 文字ちょうどで送信成功し、入力欄は maxlength=200', async () => {
    const wrapper = await mountSuspended(EmergencyClosureForm, { props: { teamId: 'team-slug' } })
    const vm = wrapper.vm as unknown as FormVm
    await fill(wrapper, { reason: 'あ'.repeat(200), subject: 'い'.repeat(200), body: '本文' })
    expect(wrapper.find('[data-testid="closure-reason"]').attributes('maxlength')).toBe('200')
    expect(wrapper.find('[data-testid="closure-subject"]').attributes('maxlength')).toBe('200')
    vm.openConfirm()
    await vm.confirmSend()
    const body = postCalls()[0]![1].body as Record<string, string>
    expect(body.reason).toHaveLength(200)
    expect(body.subject).toHaveLength(200)
    expect(mockNotifySuccess).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  }, 60000)

  it('AC-5: 確認ダイアログに理由が表示される', async () => {
    const wrapper = await mountSuspended(EmergencyClosureConfirmDialog, {
      props: { visible: true, periodText: '11月1日', subject: '件名X', reason: '設備点検のため', cancelReservations: false },
      attachTo: document.body,
    })
    await wrapper.vm.$nextTick()
    expect(document.body.textContent).toContain('設備点検のため')
    wrapper.unmount()
  }, 60000)

  it('AC-6a: 400 の fieldErrors(reason/subject) を各入力欄の下に表示する', async () => {
    mockApi.mockImplementation(async (_url: string, opts?: MockOpts) => {
      if (opts?.method === 'POST') {
        throw { data: { error: { code: 'COMMON_001', fieldErrors: [{ field: 'reason', message: 'must not be blank' }, { field: 'subject', message: 'too long' }] } } }
      }
      return { data: [] }
    })
    const wrapper = await mountSuspended(EmergencyClosureForm, { props: { teamId: 'team-slug' } })
    const vm = wrapper.vm as unknown as FormVm
    await fill(wrapper, { reason: 'r', subject: 's', body: 'b' })
    vm.openConfirm()
    await vm.confirmSend()
    await wrapper.vm.$nextTick()
    // useErrorHandler.getFieldErrors が 'must not be blank' → required、'too long' → max_length に解決する。
    // 欄ごとに別の文言が出ること（入れ替わっていないこと）を本文で照合する。
    const requiredText = tr('required')
    const maxLengthText = tr('max_length')
    expect(requiredText).not.toBe(maxLengthText)
    expect(wrapper.find('[data-testid="closure-reason-error"]').text()).toBe(requiredText)
    expect(wrapper.find('[data-testid="closure-subject-error"]').text()).toBe(maxLengthText)
    expect(mockNotifyError).not.toHaveBeenCalled()
    wrapper.unmount()
  }, 60000)

  it('AC-6a: reason だけの fieldErrors は reason 欄にだけ表示され subject 欄には出ない', async () => {
    mockApi.mockImplementation(async (_url: string, opts?: MockOpts) => {
      if (opts?.method === 'POST') {
        throw { data: { error: { code: 'COMMON_001', fieldErrors: [{ field: 'reason', message: 'must not be blank' }] } } }
      }
      return { data: [] }
    })
    const wrapper = await mountSuspended(EmergencyClosureForm, { props: { teamId: 'team-slug' } })
    const vm = wrapper.vm as unknown as FormVm
    await fill(wrapper, { reason: 'r', subject: 's', body: 'b' })
    vm.openConfirm()
    await vm.confirmSend()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="closure-reason-error"]').text()).toBe(tr('required'))
    expect(wrapper.find('[data-testid="closure-subject-error"]').exists()).toBe(false)
    expect(mockNotifyError).not.toHaveBeenCalled()
    wrapper.unmount()
  }, 60000)

  it('カスタム: 選択→本文入力→期間変更しても入力した本文が消えない', async () => {
    const wrapper = await mountSuspended(EmergencyClosureForm, { props: { teamId: 'team-slug' } })
    const vm = wrapper.vm as unknown as FormVm & { startDate: string }
    vm.applyTemplate(3)
    await fill(wrapper, { reason: '理由', subject: '件名', body: '手で書いた本文' })
    vm.startDate = '2026-12-24'
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    expect((wrapper.find('[data-testid="closure-body"]').element as HTMLTextAreaElement).value).toBe('手で書いた本文')
    wrapper.unmount()
  }, 60000)

  it('定型テンプレート: 期間を変えると本文の期間表記は従来どおり差し替わる', async () => {
    const wrapper = await mountSuspended(EmergencyClosureForm, { props: { teamId: 'team-slug' } })
    const vm = wrapper.vm as unknown as FormVm & { startDate: string, endDate: string }
    vm.applyTemplate(0)
    await wrapper.vm.$nextTick()
    const before = (wrapper.find('[data-testid="closure-body"]').element as HTMLTextAreaElement).value
    vm.startDate = '2026-12-24'
    vm.endDate = '2026-12-25'
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    const after = (wrapper.find('[data-testid="closure-body"]').element as HTMLTextAreaElement).value
    expect(after).not.toBe(before)
    expect(after).toContain('24')
    wrapper.unmount()
  }, 60000)

  it('AC-6b/6c: フィールドエラー無しの失敗は send_failed、入力は保持され loading 解除・成功表示なし', async () => {
    mockApi.mockImplementation(async (_url: string, opts?: MockOpts) => {
      if (opts?.method === 'POST') throw new Error('boom')
      return { data: [] }
    })
    const wrapper = await mountSuspended(EmergencyClosureForm, { props: { teamId: 'team-slug' } })
    const vm = wrapper.vm as unknown as FormVm
    await fill(wrapper, { reason: '理由B', subject: '件名B', body: '本文B' })
    vm.openConfirm()
    await vm.confirmSend()
    await wrapper.vm.$nextTick()
    expect(mockNotifyError).toHaveBeenCalledTimes(1)
    expect(String(mockNotifyError.mock.calls[0]![0])).toContain(tr('emergency_closure.error.send_failed'))
    expect(wrapper.find('[data-testid="closure-reason-error"]').exists()).toBe(false)
    expect((wrapper.find('[data-testid="closure-reason"]').element as HTMLInputElement).value).toBe('理由B')
    expect((wrapper.find('[data-testid="closure-subject"]').element as HTMLInputElement).value).toBe('件名B')
    expect((wrapper.find('[data-testid="closure-body"]').element as HTMLTextAreaElement).value).toBe('本文B')
    expect(vm.sendLoading).toBe(false)
    expect(vm.sendResult).toBeNull()
    expect(mockNotifySuccess).not.toHaveBeenCalled()
    wrapper.unmount()
  }, 60000)

  it('AC-7: POST 成功後に履歴取得だけ失敗しても成功と件数を保持し失敗を表示。再読込は GET のみ', async () => {
    let postDone = false
    let historyOk = false
    mockApi.mockImplementation(async (url: string, opts?: MockOpts) => {
      if (opts?.method === 'POST') {
        postDone = true
        return { data: closureResponse }
      }
      if (url.endsWith('/emergency-closures')) {
        if (postDone && !historyOk) throw new Error('history down')
        return { data: [closureResponse] }
      }
      return { data: [] }
    })
    const wrapper = await mountSuspended(EmergencyClosureForm, { props: { teamId: 'team-slug' } })
    const vm = wrapper.vm as unknown as FormVm
    await fill(wrapper, { reason: '理由', subject: '件名', body: '本文' })
    vm.openConfirm()
    await vm.confirmSend()
    await wrapper.vm.$nextTick()
    expect(vm.sendResult).toBe(3)
    expect(mockNotifySuccess).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain(tr('emergency_closure.message.sent_count', { count: 3 }))
    expect(wrapper.find('[data-testid="closure-history-error"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain(tr('emergency_closure.message.no_history'))
    historyOk = true
    await wrapper.find('[data-testid="closure-history-reload"]').trigger('click')
    await new Promise(r => setTimeout(r, 50))
    expect(postCalls()).toHaveLength(1)
    expect(wrapper.find('[data-testid="closure-history-error"]').exists()).toBe(false)
    wrapper.unmount()
  }, 60000)

  it('AC-7: History は loadError のとき空状態ではなくエラーを出す', async () => {
    const wrapper = await mountSuspended(EmergencyClosureHistory, {
      props: {
        loading: false,
        items: [],
        loadError: true,
        expandedClosureId: null,
        confirmations: [],
        confirmedCount: 0,
        totalCount: 0,
        confirmationsLoading: false,
        connectionState: 'LIVE',
        formatDate: (s: string) => s,
      },
    })
    expect(wrapper.find('[data-testid="closure-history-error"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain(tr('emergency_closure.message.no_history'))
    wrapper.unmount()
  }, 60000)
})
