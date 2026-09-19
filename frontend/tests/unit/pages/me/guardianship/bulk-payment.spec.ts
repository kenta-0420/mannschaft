import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, h, nextTick } from 'vue'

const getPayableDues = vi.fn()
const createConnectCheckout = vi.fn()
const getConnectCheckoutStatus = vi.fn()
const retrievePaymentIntent = vi.fn()
const routeState = { path: '/me/guardianship/bulk-payment', query: {} as Record<string, string> }

interface PageVm {
  selected: Set<string>
  handleBulkCheckout: () => void
  confirmCheckout: () => void
}

vi.mock('~/composables/usePayableDuesApi', () => ({
  usePayableDuesApi: () => ({ getPayableDues, bulkCheckout: vi.fn() }),
}))
vi.mock('~/composables/usePaymentApi', () => ({
  usePaymentApi: () => ({ createConnectCheckout, getConnectCheckoutStatus }),
}))
vi.mock('~/composables/useStripeSetup', () => ({
  useStripeSetup: () => ({ retrievePaymentIntent }),
}))
mockNuxtImport('useRoute', () => () => routeState)

const Page = (await import('~/pages/me/guardianship/bulk-payment.vue')).default

const dues = [
  { beneficiaryUserId: 10, beneficiaryDisplayName: '子', scopeType: 'team', scopeId: 1, scopeName: 'Team', paymentItemId: 7, itemName: '会費', faceAmount: 1000, payerSurcharge: 0, totalCharge: 1000, dueDate: null, kind: 'ONE_TIME', authorizationVia: 'GUARDIAN', alreadyPaid: false, paidByUserId: null, paidByDisplayName: null, paidAt: null },
  { beneficiaryUserId: 10, beneficiaryDisplayName: '子', scopeType: 'team', scopeId: 1, scopeName: 'Team', paymentItemId: 8, itemName: '登録料', faceAmount: 2000, payerSurcharge: 0, totalCharge: 2000, dueDate: null, kind: 'ONE_TIME', authorizationVia: 'GUARDIAN', alreadyPaid: false, paidByUserId: null, paidByDisplayName: null, paidAt: null },
] as const

beforeEach(() => {
  sessionStorage.clear()
  routeState.query = {}
  getPayableDues.mockReset().mockResolvedValue({ data: { items: [...dues] } })
  createConnectCheckout.mockReset().mockResolvedValue({ data: { clientSecret: 'pi_secret', memberPaymentId: 1, escrowTransactionId: 'escrow-1' } })
  getConnectCheckoutStatus.mockReset().mockResolvedValue({ data: { memberPaymentId: 1, status: 'PAID' } })
  retrievePaymentIntent.mockReset().mockResolvedValue({ status: 'ok', paymentIntent: { status: 'succeeded' } })
})

async function mountPage() {
  const DialogStub = defineComponent({
    props: { visible: Boolean },
    setup(_, { slots }) {
      return () => h('div', { 'data-dialog-visible': 'true' }, [slots.header?.(), slots.default?.(), slots.footer?.()])
    },
  })
  return mountSuspended(Page, {
    global: { stubs: { StripePaymentForm: true, Dialog: DialogStub, Button: true, Checkbox: true, Tag: true } },
  })
}

describe('me/guardianship/bulk-payment', () => {
  it('確認前はCheckoutを起票せず、確認後だけ安定キー付きで起票する', async () => {
    const wrapper = await mountPage()
    const vm = wrapper.vm as unknown as PageVm
    vm.selected = new Set(['10:7'])
    vm.handleBulkCheckout()
    expect(createConnectCheckout).not.toHaveBeenCalled()
    vm.confirmCheckout()
    await nextTick()
    expect(createConnectCheckout).toHaveBeenCalledWith(7, 10, expect.any(String))
    expect(sessionStorage.getItem('cmp011:payment:10:7')).toEqual(expect.any(String))
  })

  it('複数明細の確認文言に件数・受領先・合計・部分成功説明を表示する', async () => {
    const wrapper = await mountPage()
    const vm = wrapper.vm as unknown as PageVm
    vm.selected = new Set(['10:7', '10:8'])
    vm.handleBulkCheckout()
    await nextTick()
    const text = wrapper.text()
    expect(text).toContain('You are about to make 2 payment(s).')
    expect(text).toContain('Payments are processed separately for each recipient.')
    expect(text).toContain('Total: ¥3,000')
  })

  it('起票失敗時に後続明細を自動起票しない', async () => {
    createConnectCheckout.mockRejectedValueOnce(new Error('failed'))
    const wrapper = await mountPage()
    const vm = wrapper.vm as unknown as PageVm
    vm.selected = new Set(['10:7', '10:8'])
    vm.confirmCheckout()
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(createConnectCheckout).toHaveBeenCalledTimes(1)
  })

  it('3DS復帰時はPaymentIntent確認後にWebhook反映確認へ進む', async () => {
    routeState.query = { payment_intent_client_secret: 'pi_secret' }
    sessionStorage.setItem('cmp011:payment:batch', JSON.stringify(['10:7']))
    sessionStorage.setItem('cmp011:payment:current', JSON.stringify({
      selectionKey: '10:7',
      memberPaymentId: 1,
      item: dues[0],
    }))
    const wrapper = await mountPage()
    await vi.waitFor(() => expect(retrievePaymentIntent).toHaveBeenCalledWith('pi_secret'), { timeout: 5000 })
    expect(wrapper.text()).not.toContain('payment.guardianBulkPayment.result.checkedOut')
  })

  it('Webhook先行で未払い一覧から消えた明細も保存済み追跡IDでPAIDへ復元する', async () => {
    getPayableDues.mockResolvedValue({ data: { items: [] } })
    sessionStorage.setItem('cmp011:payment:batch', JSON.stringify(['10:7']))
    sessionStorage.setItem('cmp011:payment:current', JSON.stringify({
      selectionKey: '10:7',
      memberPaymentId: 1,
      item: dues[0],
    }))

    await mountPage()

    await vi.waitFor(() => expect(getConnectCheckoutStatus).toHaveBeenCalledWith(7, 1), { timeout: 5000 })
    expect(createConnectCheckout).not.toHaveBeenCalled()
  })
})
