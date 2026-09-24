import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, h, nextTick } from 'vue'
import { flushPromises } from '@vue/test-utils'

/**
 * 価格改定 詳細画面の操作導線（2026-09-24 検分指摘・御裁可分）。
 *
 * - 取り消し（cancel）: DRAFT / READY / PROVISION_FAILED のときだけボタンを出し、確認ダイアログを経て
 *   `cancelPriceRevision` を呼ぶ（修復できない失敗で商品の future 枠が永久に塞がるのを解く出口）。
 * - reconcile: 回収は PROVISIONING で停滞した revision 専用（PROVISION_FAILED は retry の責務）。
 *   staleThreshold 未満で BE が 409 PRICE_REVISION_020（PROVISION_IN_PROGRESS）を返したときは
 *   「処理中」の文言を出す（汎用の状態競合文言に潰さない）。
 */

const getPriceRevision = vi.fn()
const cancelPriceRevision = vi.fn()
const reconcileProvisionPriceRevision = vi.fn()
const notifySuccess = vi.fn()
const notifyError = vi.fn()

mockNuxtImport('useBillingApi', () => () => ({
  getPriceRevision,
  cancelPriceRevision,
  reconcileProvisionPriceRevision,
  provisionPriceRevision: vi.fn(),
  retryProvisionPriceRevision: vi.fn(),
  activatePriceRevision: vi.fn(),
}))
mockNuxtImport('useAuthStore', () => () => ({ isSystemAdmin: true, user: { timezone: 'Asia/Tokyo' }, loadFromStorage: vi.fn() }))
mockNuxtImport('useRoute', () => () => ({ params: { id: 'rev-1' }, query: {}, path: '/system-admin/price-revisions/rev-1' }))
mockNuxtImport('useNotification', () => () => ({ success: notifySuccess, error: notifyError }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))

const Page = (await import('~/pages/system-admin/price-revisions/[id].vue')).default

function revision(status: string) {
  return {
    id: 'rev-1', productKind: 'PLAN', productKey: 'FULL', scopeKind: 'TEAM', revisionNo: 1,
    catalogRevision: 'REV-1', status, lockVersion: 3, effectiveFrom: '2026-10-01T00:00:00Z',
    effectiveUntil: null,
    bands: [{ id: 'b1', bandNo: 1, minMembers: 1, maxMembers: null, inputAmount: 1000,
      taxBehavior: 'EXCLUSIVE', taxCode: 'JP_STANDARD_10', amountExcludingTax: 1000, taxAmount: 100,
      amountIncludingTax: 1100, taxRateBasisPoints: 1000, status, provisionAttempts: 1 }],
  }
}

const ButtonStub = defineComponent({
  inheritAttrs: false,
  props: { label: { type: String, default: '' } },
  setup(props, { attrs }) {
    return () => h('button', { ...attrs }, props.label)
  },
})

async function mountPage(status: string) {
  getPriceRevision.mockResolvedValue({ data: revision(status) })
  const wrapper = await mountSuspended(Page, {
    global: { stubs: { Button: ButtonStub, Column: true, DataTable: true, Tag: true } },
  })
  await flushPromises()
  await nextTick()
  return wrapper
}

beforeEach(() => {
  getPriceRevision.mockReset()
  cancelPriceRevision.mockReset().mockResolvedValue({ data: revision('CANCELLED') })
  reconcileProvisionPriceRevision.mockReset()
  notifySuccess.mockReset()
  notifyError.mockReset()
})

afterEach(() => {
  vi.restoreAllMocks()
})

/** テスト環境の window.confirm は未定義のため、確認ダイアログの応答を差し替える。 */
function stubConfirm(answer: boolean) {
  Object.defineProperty(window, 'confirm', { value: vi.fn(() => answer), writable: true, configurable: true })
}

describe('/system-admin/price-revisions/[id] 取り消し', () => {
  it.each(['DRAFT', 'READY', 'PROVISION_FAILED'])('%s では取り消しボタンを出し、確認後に lockVersion 付きで取り消す', async (status) => {
    stubConfirm(true)
    const wrapper = await mountPage(status)

    const button = wrapper.find('button[aria-label="cancel-price-revision"]')
    expect(button.exists()).toBe(true)
    await button.trigger('click')
    await flushPromises()

    expect(window.confirm).toHaveBeenCalledTimes(1)
    expect(cancelPriceRevision).toHaveBeenCalledWith('rev-1', 3, expect.any(String))
    expect(notifySuccess).toHaveBeenCalledTimes(1)
  })

  it('確認ダイアログで取り消しを選ばなければ API を呼ばない', async () => {
    stubConfirm(false)
    const wrapper = await mountPage('PROVISION_FAILED')

    await wrapper.find('button[aria-label="cancel-price-revision"]').trigger('click')
    await flushPromises()

    expect(cancelPriceRevision).not.toHaveBeenCalled()
  })

  it.each(['PROVISIONING', 'SCHEDULED', 'ACTIVE', 'RETIRED', 'CANCELLED'])('%s では取り消しボタンを出さない', async (status) => {
    const wrapper = await mountPage(status)
    expect(wrapper.find('button[aria-label="cancel-price-revision"]').exists()).toBe(false)
  })
})

describe('/system-admin/price-revisions/[id] reconcile', () => {
  it('PROVISION_FAILED では reconcile ボタンを出さない（再実行は retry の責務）', async () => {
    const wrapper = await mountPage('PROVISION_FAILED')
    expect(wrapper.find('button[aria-label="reconcile-provision-price-revision"]').exists()).toBe(false)
  })

  it('PROVISIONING では reconcile ボタンを出し、409 PRICE_REVISION_020 は「処理中」の文言で知らせる', async () => {
    reconcileProvisionPriceRevision.mockRejectedValue({
      statusCode: 409, data: { error: { code: 'PRICE_REVISION_020' } },
    })
    const wrapper = await mountPage('PROVISIONING')

    const button = wrapper.find('button[aria-label="reconcile-provision-price-revision"]')
    expect(button.exists()).toBe(true)
    await button.trigger('click')
    await flushPromises()

    expect(notifyError).toHaveBeenCalledTimes(1)
    const i18n = useNuxtApp().$i18n as { t: (key: string) => string }
    expect(notifyError.mock.calls[0]![0]).toBe(i18n.t('billing.priceRevisions.errorProvisionInProgress'))
    expect(notifyError.mock.calls[0]![0]).not.toBe(i18n.t('billing.priceRevisions.errorStateConflict'))
  })
})
