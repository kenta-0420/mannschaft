import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import PaymentAdminPanel from '~/components/payment/PaymentAdminPanel.vue'
import PaymentRecordDialog from '~/components/payment/PaymentRecordDialog.vue'
import PaymentBulkRecordDialog from '~/components/payment/PaymentBulkRecordDialog.vue'
import type { MemberPaymentResponse, PaymentItemResponse } from '~/types/payment'
import type { MemberResponse } from '~/types/member'

/**
 * F08.9 手動入金管理UI ユニットテスト（AC-16〜20）。
 *
 *  AC-16: 記録ダイアログの決済手段は CASH / BANK_TRANSFER / MANUAL の3択のみ（STRIPE 不在）
 *  AC-17: 記録成功で reload + showSuccess、失敗で showError
 *  AC-18: 各行に決済手段ラベルを表示（paymentMethod=null は非表示）
 *  AC-19: PAID 行のみ取消可・cancelPayment 呼出 + reload + toast
 *  AC-20: 一括ダイアログで未払いを複数選択 → bulkRecordPayment 呼出 + サマリー
 *  (org) organization スコープで loadScopeMembers が useOrganizationApi.getMembers を使う
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
const mockGetBeneficiarySetting = vi.fn()
const mockUpdateBeneficiarySetting = vi.fn()

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
    getBeneficiarySetting: mockGetBeneficiarySetting,
    updateBeneficiarySetting: mockUpdateBeneficiarySetting,
  }),
}))

// === useTeamApi モック（team スコープのメンバー取得） ===
const mockGetTeamMembers = vi.fn()
vi.mock('~/composables/useTeamApi', () => ({
  useTeamApi: () => ({
    getMembers: mockGetTeamMembers,
  }),
}))

// === useOrganizationApi モック（organization スコープのメンバー取得） ===
const mockGetOrgMembers = vi.fn()
vi.mock('~/composables/useOrganizationApi', () => ({
  useOrganizationApi: () => ({
    getMembers: mockGetOrgMembers,
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

/** テスト用メンバーデータ（MemberResponse 形式） */
const TEAM_MEMBERS: MemberResponse[] = [
  { userId: 100, displayName: '山田太郎', avatarUrl: null, roleName: 'MEMBER', joinedAt: '2026-01-01' },
  { userId: 200, displayName: '佐藤花子', avatarUrl: null, roleName: 'MEMBER', joinedAt: '2026-01-01' },
]

const ORG_MEMBERS: MemberResponse[] = [
  { userId: 500, displayName: '組織会員A', avatarUrl: null, roleName: 'MEMBER', joinedAt: '2026-01-01' },
  { userId: 600, displayName: '組織会員B', avatarUrl: null, roleName: 'MEMBER', joinedAt: '2026-01-01' },
]

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  mockGetPaymentItems.mockResolvedValue({ data: [buildItem()] })
  mockGetPaymentSummary.mockResolvedValue({ data: { totalMembers: 3, items: [] } })
  mockGetMemberPayments.mockResolvedValue({ data: PAYMENTS })
  // デフォルト: team スコープ用メンバー
  mockGetTeamMembers.mockResolvedValue({ data: TEAM_MEMBERS })
  // デフォルト: org スコープ用メンバー
  mockGetOrgMembers.mockResolvedValue({ data: ORG_MEMBERS })
  // デフォルト: 受益者制限設定（false=制限なし）
  mockGetBeneficiarySetting.mockResolvedValue({ data: { beneficiaryMemberOnly: false } })
  mockUpdateBeneficiarySetting.mockResolvedValue(undefined)
})

/** 項目を選択して支払い一覧をロードした状態の panel を返す（team スコープ）。 */
async function mountSelected() {
  const wrapper = await mountSuspended(PaymentAdminPanel, {
    props: { scopeType: 'team' as const, scopeId: 'team-slug-1' },
  })
  await wrapper.vm.$nextTick()
  const vm = wrapper.vm as unknown as { loadPayments: (i: PaymentItemResponse) => Promise<void> }
  await vm.loadPayments(buildItem())
  await wrapper.vm.$nextTick()
  return wrapper
}

/** organization スコープでマウントした panel を返す。 */
async function mountOrgSelected() {
  const wrapper = await mountSuspended(PaymentAdminPanel, {
    props: { scopeType: 'organization' as const, scopeId: 'org-slug-1' },
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

  it('scopeMembers を渡すとそれが memberOptions に優先される（payments フォールバックより優先）', async () => {
    const scopeMembers: MemberResponse[] = [
      { userId: 501, displayName: 'スコープ会員X', avatarUrl: null, roleName: 'MEMBER', joinedAt: '2026-01-01' },
      { userId: 502, displayName: 'スコープ会員Y', avatarUrl: null, roleName: 'MEMBER', joinedAt: '2026-01-01' },
    ]
    const wrapper = await mountSuspended(PaymentRecordDialog, {
      props: { visible: true, defaultAmount: 5000, payments: PAYMENTS, scopeMembers },
    })
    const vm = wrapper.vm as unknown as { memberOptions: Array<{ label: string; value: number }> }
    // scopeMembers 由来のオプションが出る（PAYMENTS 由来の userId 100/200/300 でなく 501/502）
    expect(vm.memberOptions.map((o) => o.value)).toEqual([501, 502])
    expect(vm.memberOptions.map((o) => o.label)).toEqual(['スコープ会員X', 'スコープ会員Y'])
  })

  it('scopeMembers が空の場合は payments からフォールバックする', async () => {
    const wrapper = await mountSuspended(PaymentRecordDialog, {
      props: { visible: true, defaultAmount: 5000, payments: PAYMENTS, scopeMembers: [] },
    })
    const vm = wrapper.vm as unknown as { memberOptions: Array<{ label: string; value: number }> }
    // payments 由来のオプション（userId 100/200/300）
    expect(vm.memberOptions.map((o) => o.value)).toEqual([100, 200, 300])
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

  it('AC-20: onBulkSubmit 成功で bulkRecordPayment + reload + サマリートースト（BE実数の created/skipped を反映）', async () => {
    // BE は createdCount / skippedCount を返す。送信件数ではなく BE 実数でサマリーを出すこと。
    mockBulkRecordPayment.mockResolvedValueOnce({
      data: { createdCount: 1, skippedCount: 1, skipped: [{ userId: 999, reason: 'ALREADY_PAID' }] },
    })
    const wrapper = await mountSelected()
    const vm = wrapper.vm as unknown as { onBulkSubmit: (b: Array<Record<string, unknown>>) => Promise<void> }
    const before = mockGetMemberPayments.mock.calls.length
    await vm.onBulkSubmit([
      { userId: 200, amountPaid: 5000, paidAt: '2026-06-23T00:00:00', paymentMethod: 'CASH' },
      { userId: 999, amountPaid: 5000, paidAt: '2026-06-23T00:00:00', paymentMethod: 'CASH' },
    ])
    expect(mockBulkRecordPayment).toHaveBeenCalledTimes(1)
    expect(mockGetMemberPayments.mock.calls.length).toBe(before + 1)
    expect(mockShowSuccess).toHaveBeenCalledTimes(1)
    // サマリーに BE 実数（created=1 / skipped=1）が渡っていること
    const summaryArg = mockShowSuccess.mock.calls[0]?.[0] as string
    expect(summaryArg).toContain('1')
  })

  it('AC-16: 記録ダイアログを開くボタンが存在する', async () => {
    const wrapper = await mountSelected()
    expect(wrapper.find('[data-testid="payment-record-open"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="payment-bulk-open"]').exists()).toBe(true)
  })

  it('(org) team スコープでは useTeamApi.getMembers を呼び scopeMembers を設定する', async () => {
    await mountSelected()
    // team スコープのため useTeamApi.getMembers が呼ばれる
    expect(mockGetTeamMembers).toHaveBeenCalledTimes(1)
    expect(mockGetTeamMembers).toHaveBeenCalledWith('team-slug-1', { size: 200 })
    // useOrganizationApi.getMembers は呼ばれない
    expect(mockGetOrgMembers).not.toHaveBeenCalled()
  })
})

describe('PaymentAdminPanel.vue — organization スコープ（org メンバー選択肢）', () => {
  it('(org) organization スコープでは useOrganizationApi.getMembers を呼び scopeMembers を設定する', async () => {
    await mountOrgSelected()
    // org スコープのため useOrganizationApi.getMembers が呼ばれる
    expect(mockGetOrgMembers).toHaveBeenCalledTimes(1)
    expect(mockGetOrgMembers).toHaveBeenCalledWith('org-slug-1', { size: 200 })
    // useTeamApi.getMembers は呼ばれない
    expect(mockGetTeamMembers).not.toHaveBeenCalled()
  })

  it('(org) organization スコープで記録ダイアログの scopeMembers が org メンバー由来になる', async () => {
    const wrapper = await mountOrgSelected()
    const vm = wrapper.vm as unknown as { scopeMembers: MemberResponse[] }
    // ORG_MEMBERS のデータが scopeMembers にセットされている
    const userIds = vm.scopeMembers.map((m) => m.userId)
    expect(userIds).toContain(500)
    expect(userIds).toContain(600)
    expect(userIds).not.toContain(100) // team メンバーは含まれない
  })

  it('(org) organization スコープで getMembers 失敗時は scopeMembers が空のまま（payments フォールバック用）', async () => {
    mockGetOrgMembers.mockRejectedValueOnce(new Error('API error'))
    const wrapper = await mountOrgSelected()
    const vm = wrapper.vm as unknown as { scopeMembers: MemberResponse[] }
    // エラー時はフォールバック（空配列のまま）
    expect(vm.scopeMembers).toHaveLength(0)
  })
})

describe('PaymentAdminPanel.vue — AC-S8: 受益者制限設定トグル', () => {
  it('AC-S8: マウント時に getBeneficiarySetting を呼び初期値を反映する', async () => {
    mockGetBeneficiarySetting.mockResolvedValueOnce({ data: { beneficiaryMemberOnly: true } })
    const wrapper = await mountSuspended(PaymentAdminPanel, {
      props: { scopeType: 'team' as const, scopeId: 'team-slug-1' },
    })
    await wrapper.vm.$nextTick()
    expect(mockGetBeneficiarySetting).toHaveBeenCalledTimes(1)
    expect(mockGetBeneficiarySetting).toHaveBeenCalledWith('team', 'team-slug-1')
    const vm = wrapper.vm as unknown as { beneficiaryMemberOnly: boolean }
    expect(vm.beneficiaryMemberOnly).toBe(true)
  })

  it('AC-S8: トグルが DOM に存在する（data-testid="beneficiary-member-only-toggle"）', async () => {
    const wrapper = await mountSuspended(PaymentAdminPanel, {
      props: { scopeType: 'team' as const, scopeId: 'team-slug-1' },
    })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="beneficiary-member-only-toggle"]').exists()).toBe(true)
  })

  it('AC-S8: onBeneficiarySettingChange 成功で updateBeneficiarySetting を呼び showSuccess を出す', async () => {
    mockUpdateBeneficiarySetting.mockResolvedValueOnce(undefined)
    const wrapper = await mountSuspended(PaymentAdminPanel, {
      props: { scopeType: 'team' as const, scopeId: 'team-slug-1' },
    })
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as unknown as {
      onBeneficiarySettingChange: (value: boolean) => Promise<void>
      beneficiaryMemberOnly: boolean
    }
    await vm.onBeneficiarySettingChange(true)
    expect(mockUpdateBeneficiarySetting).toHaveBeenCalledWith('team', 'team-slug-1', true)
    expect(mockShowSuccess).toHaveBeenCalledTimes(1)
  })

  it('AC-S8: onBeneficiarySettingChange 失敗でトグルを元に戻し showError を出す', async () => {
    mockUpdateBeneficiarySetting.mockRejectedValueOnce(new Error('403 Forbidden'))
    const wrapper = await mountSuspended(PaymentAdminPanel, {
      props: { scopeType: 'team' as const, scopeId: 'team-slug-1' },
    })
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as unknown as {
      onBeneficiarySettingChange: (value: boolean) => Promise<void>
      beneficiaryMemberOnly: boolean
    }
    // 初期値 false → true に切り替えて失敗
    vm.beneficiaryMemberOnly = false
    await vm.onBeneficiarySettingChange(true)
    // 失敗したため false に戻る
    expect(vm.beneficiaryMemberOnly).toBe(false)
    expect(mockShowError).toHaveBeenCalledTimes(1)
  })

  it('AC-S8: getBeneficiarySetting 失敗時は beneficiaryMemberOnly が false のまま（フォールバック）', async () => {
    mockGetBeneficiarySetting.mockRejectedValueOnce(new Error('500 Internal Server Error'))
    const wrapper = await mountSuspended(PaymentAdminPanel, {
      props: { scopeType: 'team' as const, scopeId: 'team-slug-1' },
    })
    await wrapper.vm.$nextTick()
    const vm = wrapper.vm as unknown as { beneficiaryMemberOnly: boolean }
    expect(vm.beneficiaryMemberOnly).toBe(false)
  })
})
