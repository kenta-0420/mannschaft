import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import PaymentAdminPanel from '~/components/payment/PaymentAdminPanel.vue'
import PaymentRecordDialog from '~/components/payment/PaymentRecordDialog.vue'
import PaymentBulkRecordDialog from '~/components/payment/PaymentBulkRecordDialog.vue'
import type { MemberPaymentResponse, PaymentItemResponse } from '~/types/payment'

/**
 * F08.9 手動入金管理UI ユニットテスト（AC-16〜20）。
 *
 *  AC-16: 記録ダイアログの決済手段は CASH / BANK_TRANSFER / MANUAL の3択のみ（STRIPE 不在）
 *  AC-17: 記録成功で reload + showSuccess、失敗で showError
 *  AC-18: 各行に決済手段ラベルを表示（paymentMethod=null は非表示）
 *  AC-19: PAID 行のみ取消可・cancelPayment 呼出 + reload + toast
 *  AC-20: 一括ダイアログで未払いを複数選択 → bulkRecordPayment 呼出 + サマリー
 */

// === API モック ===
const mockGetPaymentItems = vi.fn()
const mockGetMemberPayments = vi.fn()
const mockGetPaymentSummary = vi.fn()
const mockRecordManualPayment = vi.fn()
const mockBulkRecordPayment = vi.fn()
const mockCancelPayment = vi.fn()
const mockSendReminder = vi.fn()
const mockExportPayments = vi.fn()

vi.mock('~/composables/usePaymentApi', () => ({
  usePaymentApi: () => ({
    getPaymentItems: mockGetPaymentItems,
    getMemberPayments: mockGetMemberPayments,
    getPaymentSummary: mockGetPaymentSummary,
    recordManualPayment: mockRecordManualPayment,
    bulkRecordPayment: mockBulkRecordPayment,
    cancelPayment: mockCancelPayment,
    sendReminder: mockSendReminder,
    exportPayments: mockExportPayments,
  }),
}))

const mockShowSuccess = vi.fn()
const mockShowError = vi.fn()
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: mockShowSuccess,
    info: vi.fn(),
    warn: vi.fn(),
    error: mockShowError,
    showSuccess: mockShowSuccess,
    showError: mockShowError,
    showInfo: vi.fn(),
    showWarn: vi.fn(),
  }),
}))

function buildItem(): PaymentItemResponse {
  return {
    id: 10,
    meta: { name: '年会費', description: null, type: 'ANNUAL_FEE', displayOrder: 0, gracePeriodDays: 0 },
    money: { amount: 5000, currency: 'JPY' },
    stripe: { stripeProductId: null, stripePriceId: null },
    term: null,
    audit: { isActive: true, createdAt: '2026-01-01T00:00:00', updatedAt: null },
  }
}

function buildPayment(overrides: Partial<MemberPaymentResponse> = {}): MemberPaymentResponse {
  return {
    id: 1,
    userId: 100,
    userName: '山田太郎',
    paymentItemId: 10,
    paymentMethod: null,
    money: { amountPaid: null, currency: 'JPY' },
    statusInfo: { status: 'UNPAID', validFrom: null, validUntil: null, paidAt: null },
    refund: { stripeRefundId: null, stripeReceiptUrl: null, refundedAt: null },
    audit: { note: null, createdAt: null, updatedAt: null },
    ...overrides,
  }
}

const PAYMENTS: MemberPaymentResponse[] = [
  buildPayment({ id: 1, userId: 100, userName: '山田太郎', paymentMethod: 'CASH', statusInfo: { status: 'PAID', validFrom: null, validUntil: null, paidAt: '2026-06-01' } }),
  buildPayment({ id: 2, userId: 200, userName: '佐藤花子', paymentMethod: null, statusInfo: { status: 'UNPAID', validFrom: null, validUntil: null, paidAt: null } }),
  buildPayment({ id: 3, userId: 300, userName: '鈴木一郎', paymentMethod: 'BANK_TRANSFER', statusInfo: { status: 'PAID', validFrom: null, validUntil: null, paidAt: '2026-06-02' } }),
]

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  mockGetPaymentItems.mockResolvedValue({ data: [buildItem()] })
  mockGetPaymentSummary.mockResolvedValue({ data: { totalMembers: 3, items: [] } })
  mockGetMemberPayments.mockResolvedValue({ data: PAYMENTS })
})

/** 項目を選択して支払い一覧をロードした状態の panel を返す。 */
async function mountSelected() {
  const wrapper = await mountSuspended(PaymentAdminPanel, {
    props: { scopeType: 'team' as const, scopeId: '7' },
  })
  await wrapper.vm.$nextTick()
  const vm = wrapper.vm as unknown as { loadPayments: (i: PaymentItemResponse) => Promise<void> }
  await vm.loadPayments(buildItem())
  await wrapper.vm.$nextTick()
  return wrapper
}

describe('PaymentRecordDialog.vue (AC-16/AC-17)', () => {
  it('AC-16: 決済手段の選択肢は CASH/BANK_TRANSFER/MANUAL の3択で STRIPE を含まない', async () => {
    const wrapper = await mountSuspended(PaymentRecordDialog, {
      props: { visible: true, defaultAmount: 5000, payments: PAYMENTS },
    })
    const vm = wrapper.vm as unknown as { methodOptions: Array<{ value: string }> }
    const values = vm.methodOptions.map((o) => o.value)
    expect(values).toEqual(['CASH', 'BANK_TRANSFER', 'MANUAL'])
    expect(values).not.toContain('STRIPE')
  })

  it('AC-17(body): submit で camelCase body と LocalDateTime 形式の paidAt を emit する', async () => {
    const wrapper = await mountSuspended(PaymentRecordDialog, {
      props: { visible: true, defaultAmount: 5000, payments: PAYMENTS },
    })
    const vm = wrapper.vm as unknown as {
      userId: number | null
      amountPaid: number
      paymentMethod: string
      onSubmit: () => void
    }
    vm.userId = 200
    vm.amountPaid = 5000
    vm.paymentMethod = 'BANK_TRANSFER'
    await wrapper.vm.$nextTick()
    vm.onSubmit()

    const emitted = wrapper.emitted('submit')
    expect(emitted).toBeTruthy()
    const body = emitted?.[0]?.[0] as Record<string, unknown>
    expect(body.userId).toBe(200)
    expect(body.amountPaid).toBe(5000)
    expect(body.paymentMethod).toBe('BANK_TRANSFER')
    expect(body.paidAt).toMatch(/^\d{4}-\d{2}-\d{2}T00:00:00$/)
    expect(body.paymentMethod).not.toBe('STRIPE')
  })
})

describe('PaymentBulkRecordDialog.vue (AC-20)', () => {
  it('未払い(UNPAID)のみを候補にする', async () => {
    const wrapper = await mountSuspended(PaymentBulkRecordDialog, {
      props: { visible: true, defaultAmount: 5000, payments: PAYMENTS },
    })
    const vm = wrapper.vm as unknown as { unpaidMembers: MemberPaymentResponse[] }
    expect(vm.unpaidMembers.map((m) => m.userId)).toEqual([200])
  })

  it('AC-20: 複数選択して submit すると各 body 配列を emit する', async () => {
    const payments = [
      buildPayment({ id: 2, userId: 200, userName: 'A', statusInfo: { status: 'UNPAID', validFrom: null, validUntil: null, paidAt: null } }),
      buildPayment({ id: 4, userId: 400, userName: 'B', statusInfo: { status: 'UNPAID', validFrom: null, validUntil: null, paidAt: null } }),
    ]
    const wrapper = await mountSuspended(PaymentBulkRecordDialog, {
      props: { visible: true, defaultAmount: 3000, payments },
    })
    const vm = wrapper.vm as unknown as {
      toggle: (id: number) => void
      onSubmit: () => void
    }
    vm.toggle(200)
    vm.toggle(400)
    await wrapper.vm.$nextTick()
    vm.onSubmit()

    const emitted = wrapper.emitted('submit')
    const bodies = emitted?.[0]?.[0] as Array<Record<string, unknown>>
    expect(bodies).toHaveLength(2)
    expect(bodies.map((b) => b.userId).sort()).toEqual([200, 400])
    expect(bodies[0]?.amountPaid).toBe(3000)
    expect(bodies[0]?.paidAt).toMatch(/^\d{4}-\d{2}-\d{2}T00:00:00$/)
  })
})

describe('PaymentAdminPanel.vue (AC-17/18/19/20)', () => {
  it('AC-18: PAID 行は決済手段ラベルを表示し、paymentMethod=null の行は非表示', async () => {
    const wrapper = await mountSelected()
    // CASH 行（id=1）はラベル表示
    expect(wrapper.find('[data-testid="payment-method-1"]').exists()).toBe(true)
    // null 行（id=2）はラベル非表示
    expect(wrapper.find('[data-testid="payment-method-2"]').exists()).toBe(false)
  })

  it('AC-19: PAID 行のみ取消ボタンを表示し、UNPAID 行には出さない', async () => {
    const wrapper = await mountSelected()
    expect(wrapper.find('[data-testid="payment-cancel-1"]').exists()).toBe(true) // PAID
    expect(wrapper.find('[data-testid="payment-cancel-3"]').exists()).toBe(true) // PAID
    expect(wrapper.find('[data-testid="payment-cancel-2"]').exists()).toBe(false) // UNPAID
  })

  it('AC-19: onCancel で cancelPayment 呼出 + reload + showSuccess', async () => {
    mockCancelPayment.mockResolvedValueOnce(undefined)
    const wrapper = await mountSelected()
    const vm = wrapper.vm as unknown as { onCancel: (p: MemberPaymentResponse) => Promise<void> }
    const before = mockGetMemberPayments.mock.calls.length
    await vm.onCancel(PAYMENTS[0]!) // PAID 行
    expect(mockCancelPayment).toHaveBeenCalledTimes(1)
    expect(mockGetMemberPayments.mock.calls.length).toBe(before + 1) // reload
    expect(mockShowSuccess).toHaveBeenCalledTimes(1)
  })

  it('AC-19: UNPAID 行への onCancel は cancelPayment を呼ばない', async () => {
    const wrapper = await mountSelected()
    const vm = wrapper.vm as unknown as { onCancel: (p: MemberPaymentResponse) => Promise<void> }
    await vm.onCancel(PAYMENTS[1]!) // UNPAID 行
    expect(mockCancelPayment).not.toHaveBeenCalled()
  })

  it('AC-17: onRecordSubmit 成功で recordManualPayment + reload + showSuccess', async () => {
    mockRecordManualPayment.mockResolvedValueOnce(undefined)
    const wrapper = await mountSelected()
    const vm = wrapper.vm as unknown as { onRecordSubmit: (b: Record<string, unknown>) => Promise<void> }
    const before = mockGetMemberPayments.mock.calls.length
    await vm.onRecordSubmit({ userId: 200, amountPaid: 5000, paidAt: '2026-06-23T00:00:00', paymentMethod: 'CASH' })
    expect(mockRecordManualPayment).toHaveBeenCalledTimes(1)
    expect(mockGetMemberPayments.mock.calls.length).toBe(before + 1)
    expect(mockShowSuccess).toHaveBeenCalledTimes(1)
  })

  it('AC-17: onRecordSubmit 失敗で showError、reload しない', async () => {
    mockRecordManualPayment.mockRejectedValueOnce(new Error('PAYMENT_400'))
    const wrapper = await mountSelected()
    const vm = wrapper.vm as unknown as { onRecordSubmit: (b: Record<string, unknown>) => Promise<void> }
    const before = mockGetMemberPayments.mock.calls.length
    await vm.onRecordSubmit({ userId: 200, amountPaid: 5000, paidAt: '2026-06-23T00:00:00', paymentMethod: 'CASH' })
    expect(mockShowError).toHaveBeenCalledTimes(1)
    expect(mockGetMemberPayments.mock.calls.length).toBe(before) // reload されない
  })

  it('AC-20: onBulkSubmit 成功で bulkRecordPayment + reload + サマリートースト', async () => {
    mockBulkRecordPayment.mockResolvedValueOnce(undefined)
    const wrapper = await mountSelected()
    const vm = wrapper.vm as unknown as { onBulkSubmit: (b: Array<Record<string, unknown>>) => Promise<void> }
    const before = mockGetMemberPayments.mock.calls.length
    await vm.onBulkSubmit([
      { userId: 200, amountPaid: 5000, paidAt: '2026-06-23T00:00:00', paymentMethod: 'CASH' },
    ])
    expect(mockBulkRecordPayment).toHaveBeenCalledTimes(1)
    expect(mockGetMemberPayments.mock.calls.length).toBe(before + 1)
    expect(mockShowSuccess).toHaveBeenCalledTimes(1)
  })

  it('AC-16: 記録ダイアログを開くボタンが存在する', async () => {
    const wrapper = await mountSelected()
    expect(wrapper.find('[data-testid="payment-record-open"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="payment-bulk-open"]').exists()).toBe(true)
  })
})
