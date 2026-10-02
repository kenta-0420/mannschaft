import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, h } from 'vue'

/**
 * 価格改定 管理画面の日時送信値: DST 境界（2026-09-24 Codex 再検分 P2）。
 *
 * ブラウザTZ=America/New_York（2026-03-08 02:00 に DST 開始＝02:30 は存在しない）、ユーザーTZ=Asia/Tokyo で、
 * datetime-local に入力した `2026-03-08T02:30` が `2026-03-08T02:30:00+09:00` として送られること
 * （`new Date()` 経由の 1 時間ずれ＝03:30 にならないこと）を、実際に API へ渡るペイロードで確かめる。
 */

const originalTz = process.env.TZ
process.env.TZ = 'America/New_York'

const createPriceRevision = vi.fn()
const createTaxCode = vi.fn()

mockNuxtImport('useBillingApi', () => () => ({
  createPriceRevision,
  createTaxCode,
  listPriceRevisions: vi.fn().mockResolvedValue({ data: { items: [], totalElements: 0 } }),
  listTaxCodes: vi.fn().mockResolvedValue([]),
  updateTaxCode: vi.fn(),
}))
mockNuxtImport('useAuthStore', () => () => ({ isSystemAdmin: true, user: { timezone: 'Asia/Tokyo' }, loadFromStorage: vi.fn() }))

const Page = (await import('~/pages/system-admin/price-revisions/index.vue')).default

interface PageVm {
  form: { productKey: string; effectiveFrom: string }
  taxMode: 'EXCLUSIVE' | 'INCLUSIVE' | null
  bandForm: { inputAmount: number | null; taxCode: string }
  createPriceRevision: () => Promise<void>
  taxCodeForm: { code: string; displayName: string; validFrom: string }
  submitCreateTaxCode: () => Promise<void>
}

afterAll(() => {
  process.env.TZ = originalTz
})

beforeEach(() => {
  createPriceRevision.mockReset().mockResolvedValue({ data: { id: 'rev-1', status: 'DRAFT' } })
  createTaxCode.mockReset().mockResolvedValue({})
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

describe('/system-admin/price-revisions DST 境界の日時送信値', () => {
  it('effectiveFrom の DST 開始時刻（ブラウザTZで存在しない）を1時間ずらさずユーザーTZで送る', async () => {
    const vm = (await mountPage()).vm as unknown as PageVm
    vm.form.productKey = 'FULL'
    vm.form.effectiveFrom = '2026-03-08T02:30'
    vm.taxMode = 'EXCLUSIVE'
    vm.bandForm.inputAmount = 1000
    vm.bandForm.taxCode = 'JP_STANDARD_10'

    await vm.createPriceRevision()

    const payload = createPriceRevision.mock.calls[0]![0] as { effectiveFrom: string }
    expect(payload.effectiveFrom).toBe('2026-03-08T02:30:00+09:00')
  })

  it('税コード validFrom の DST 開始時刻も同じ関数でユーザーTZのまま送る', async () => {
    const vm = (await mountPage()).vm as unknown as PageVm
    vm.taxCodeForm.code = 'JP_TEST_5'
    vm.taxCodeForm.displayName = 'テスト'
    vm.taxCodeForm.validFrom = '2026-03-08T02:30'

    await vm.submitCreateTaxCode()

    const payload = createTaxCode.mock.calls[0]![0] as { validFrom: string }
    expect(payload.validFrom).toBe('2026-03-08T02:30:00+09:00')
  })
})
