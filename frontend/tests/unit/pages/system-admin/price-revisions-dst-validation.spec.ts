import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, h, nextTick } from 'vue'

/**
 * 価格改定 管理画面 — ユーザーTZの DST 開始で存在しない壁時計をフォームバリデーションで止める
 * （2026-09-24 検分指摘 P2、御裁可分）。
 *
 * ユーザーTZ=America/New_York で `2026-03-08T02:30`（春・スプリングフォワードで実在しない）を
 * effectiveFrom / 税コード validFrom に入力した場合、送信ボタンが無効化され、送信もされない
 * ことを確かめる。
 */

const createPriceRevision = vi.fn()
const createTaxCode = vi.fn()
const listPriceRevisions = vi.fn()
const listTaxCodes = vi.fn()

mockNuxtImport('useBillingApi', () => () => ({
  createPriceRevision,
  createTaxCode,
  listPriceRevisions,
  listTaxCodes,
  updateTaxCode: vi.fn(),
}))

mockNuxtImport('useAuthStore', () => () => ({
  isSystemAdmin: true,
  user: { timezone: 'America/New_York' },
  loadFromStorage: vi.fn(),
}))

const Page = (await import('~/pages/system-admin/price-revisions/index.vue')).default

interface PageVm {
  form: { productKey: string; effectiveFrom: string; effectiveUntil: string }
  taxMode: 'EXCLUSIVE' | 'INCLUSIVE' | null
  bandForm: { inputAmount: number | null; taxCode: string }
  canCreate: boolean
  createPriceRevision: () => Promise<void>
  taxCodeForm: { code: string; displayName: string; validFrom: string }
  taxCodeCreateDisabled: boolean
  submitCreateTaxCode: () => Promise<void>
}

beforeEach(() => {
  createPriceRevision.mockReset().mockResolvedValue({ data: { id: 'rev-1', status: 'DRAFT' } })
  createTaxCode.mockReset().mockResolvedValue({})
  listPriceRevisions.mockReset().mockResolvedValue({ data: { items: [], totalElements: 0 } })
  listTaxCodes.mockReset().mockResolvedValue([])
})

async function mountPage() {
  const DialogStub = defineComponent({
    props: { visible: Boolean },
    setup(_, { slots }) {
      return () => h('div', [slots.header?.(), slots.default?.(), slots.footer?.()])
    },
  })
  return mountSuspended(Page, {
    global: {
      stubs: {
        Dialog: DialogStub, Button: true, Column: true, DataTable: true, Dropdown: true,
        InputNumber: true, InputText: true, Tag: true,
      },
    },
  })
}

describe('/system-admin/price-revisions DST で存在しない時刻のバリデーション', () => {
  it('effectiveFrom が存在しない壁時計のとき canCreate は false になり送信しない', async () => {
    const wrapper = await mountPage()
    const vm = wrapper.vm as unknown as PageVm
    vm.form.productKey = 'FULL'
    vm.form.effectiveFrom = '2026-03-08T02:30'
    vm.taxMode = 'EXCLUSIVE'
    vm.bandForm.inputAmount = 1000
    vm.bandForm.taxCode = 'JP_STANDARD_10'
    await nextTick()

    expect(vm.canCreate).toBe(false)

    await vm.createPriceRevision()

    expect(createPriceRevision).not.toHaveBeenCalled()
  })

  it('effectiveFrom を通常の時刻に戻せば canCreate は true になる', async () => {
    const wrapper = await mountPage()
    const vm = wrapper.vm as unknown as PageVm
    vm.form.productKey = 'FULL'
    vm.form.effectiveFrom = '2026-03-08T02:30'
    vm.taxMode = 'EXCLUSIVE'
    vm.bandForm.inputAmount = 1000
    vm.bandForm.taxCode = 'JP_STANDARD_10'
    await nextTick()
    expect(vm.canCreate).toBe(false)

    vm.form.effectiveFrom = '2026-09-24T12:00'
    await nextTick()

    expect(vm.canCreate).toBe(true)
  })

  it('税コード validFrom が存在しない壁時計のとき taxCodeCreateDisabled は true になり送信しない', async () => {
    const wrapper = await mountPage()
    const vm = wrapper.vm as unknown as PageVm
    vm.taxCodeForm.code = 'JP_TEST_5'
    vm.taxCodeForm.displayName = 'テスト'
    vm.taxCodeForm.validFrom = '2026-03-08T02:30'
    await nextTick()

    expect(vm.taxCodeCreateDisabled).toBe(true)

    await vm.submitCreateTaxCode()

    expect(createTaxCode).not.toHaveBeenCalled()
  })
})
