import { describe, expect, it, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ContentGateManager from '~/components/payment/ContentGateManager.vue'
import type { PaymentItemResponse } from '~/types/payment'

const getGates = vi.fn()
const updateGates = vi.fn()
const getItems = vi.fn()
const success = vi.fn()
const failure = vi.fn()

vi.mock('~/composables/useContentPaymentGateApi', () => ({
  useContentPaymentGateApi: () => ({ getContentPaymentGates: getGates, updateContentPaymentGates: updateGates }),
}))
vi.mock('~/composables/usePaymentApi', () => ({
  usePaymentApi: () => ({ getPaymentItems: getItems }),
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ showSuccess: success, showError: failure }),
}))

const item = (id: number, type: 'ITEM' | 'DONATION'): PaymentItemResponse => ({
  id,
  meta: { name: `item-${id}`, description: null, type, displayOrder: 0, gracePeriodDays: 0 },
  money: { amount: 100, currency: 'JPY' },
  stripe: { stripeProductId: null, stripePriceId: null },
  term: null,
  audit: { isActive: true, createdAt: '', updatedAt: null },
})

describe('ContentGateManager.vue', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getGates.mockResolvedValue({ data: [{ id: 1, content: { contentType: 'POST', contentId: 42, isTitleHidden: true }, paymentItem: { id: 7, name: '会費', type: 'ITEM', amount: 100, currency: 'JPY' }, audit: { createdBy: 1, createdAt: '' } }], meta: { page: 0, size: 50, totalElements: 1, totalPages: 1 } })
    getItems.mockResolvedValue({ data: [item(7, 'ITEM'), item(8, 'DONATION')] })
    updateGates.mockResolvedValue({ data: {} })
  })

  it('TEAMスコープで既存設定を復元し、寄付項目を除外する', async () => {
    const wrapper = await mountSuspended(ContentGateManager, { props: { scopeType: 'team', scopeId: '10' } })
    expect(getGates).toHaveBeenCalledWith('team', '10')
    expect(getItems).toHaveBeenCalledWith('team', '10')
    expect(wrapper.find('[data-testid="content-gate-item-7"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="content-gate-item-8"]').exists()).toBe(false)
    expect((wrapper.find('[data-testid="content-gate-id"]').element as HTMLInputElement).value).toBe('42')
  })

  it('ORGANIZATIONで複数項目とタイトル非表示を保存できる', async () => {
    getGates.mockResolvedValue({ data: [], meta: { page: 0, size: 50, totalElements: 0, totalPages: 0 } })
    getItems.mockResolvedValue({ data: [item(7, 'ITEM')] })
    const wrapper = await mountSuspended(ContentGateManager, { props: { scopeType: 'organization', scopeId: '20' } })
    await wrapper.find('[data-testid="content-gate-id"]').setValue(99)
    await wrapper.find('[data-testid="content-gate-item-7"]').setValue(true)
    await wrapper.find('[data-testid="content-gate-title-hidden-7"]').setValue(true)
    await wrapper.find('[data-testid="content-gate-save"]').trigger('click')
    expect(updateGates).toHaveBeenCalledWith('organization', '20', {
      contentType: 'POST', contentId: 99, gates: [{ paymentItemId: 7, isTitleHidden: true }],
    })
  })

  it('空選択を保存してゲート解除でき、失敗時はエラー通知する', async () => {
    getGates.mockResolvedValue({ data: [], meta: { page: 0, size: 50, totalElements: 0, totalPages: 0 } })
    const wrapper = await mountSuspended(ContentGateManager, { props: { scopeType: 'team', scopeId: '10' } })
    await wrapper.find('[data-testid="content-gate-id"]').setValue(10)
    updateGates.mockRejectedValueOnce(new Error('failed'))
    await wrapper.find('[data-testid="content-gate-save"]').trigger('click')
    expect(updateGates).toHaveBeenCalledWith('team', '10', { contentType: 'POST', contentId: 10, gates: [] })
    expect(failure).toHaveBeenCalled()
    expect(success).not.toHaveBeenCalled()
  })
})
