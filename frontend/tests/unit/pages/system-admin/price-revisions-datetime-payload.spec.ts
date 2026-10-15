import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, h } from 'vue'

/**
 * 価格改定 管理画面の日時送信値（Codex 検分 P1 指摘3の検体）。
 *
 * `<input type="datetime-local">` の値は `2026-09-24T12:00` のようにオフセットを持たない。
 * BE の `effectiveFrom` / `validFrom` は Java `Instant` のため、オフセット無しの文字列を
 * そのまま送ると Jackson が解釈できず 400 になる。送信前にオフセット付き ISO-8601 へ
 * 変換されていることを、実際に API へ渡るペイロードで検証する。
 *
 * 変換は `useDatetime().buildOffsetDateTimeStr()`（壁時計成分をユーザーTZで解釈する既存の正準）
 * を通すため、画面で入力した壁時計 `2026-09-24T12:00` はそのまま保たれ、オフセットだけが付く。
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
  user: { timezone: 'Asia/Tokyo' },
  loadFromStorage: vi.fn(),
}))

const Page = (await import('~/pages/system-admin/price-revisions/index.vue')).default

interface PageVm {
  form: { productKind: string; productKey: string; scopeKind: string; effectiveFrom: string; effectiveUntil: string }
  taxMode: 'EXCLUSIVE' | 'INCLUSIVE' | null
  bandForm: { inputAmount: number | null; taxCode: string }
  createPriceRevision: () => Promise<void>
  taxCodeForm: { code: string; displayName: string; validFrom: string }
  submitCreateTaxCode: () => Promise<void>
}

/** オフセット付き ISO-8601（末尾が Z または ±HH:mm）。 */
const OFFSET_ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$/

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

describe('/system-admin/price-revisions 日時送信値', () => {
  it('DRAFT 作成の effectiveFrom はオフセット付き ISO-8601 で送信され、入力した壁時計を保つ', async () => {
    const wrapper = await mountPage()
    const vm = wrapper.vm as unknown as PageVm
    vm.form.productKey = 'FULL'
    vm.form.effectiveFrom = '2026-09-24T12:00'
    vm.taxMode = 'EXCLUSIVE'
    vm.bandForm.inputAmount = 1000
    vm.bandForm.taxCode = 'JP_STANDARD_10'

    await vm.createPriceRevision()

    expect(createPriceRevision).toHaveBeenCalledTimes(1)
    const payload = createPriceRevision.mock.calls[0]![0] as { effectiveFrom: string; effectiveUntil: string | null }
    expect(payload.effectiveFrom).toMatch(OFFSET_ISO)
    expect(payload.effectiveFrom.startsWith('2026-09-24T12:00')).toBe(true)
    expect(payload.effectiveUntil).toBeNull()
  })

  it('DRAFT 作成で effectiveUntil を入れた場合もオフセット付きで送信される', async () => {
    const wrapper = await mountPage()
    const vm = wrapper.vm as unknown as PageVm
    vm.form.productKey = 'FULL'
    vm.form.effectiveFrom = '2026-09-24T12:00'
    vm.form.effectiveUntil = '2026-12-31T23:30'
    vm.taxMode = 'EXCLUSIVE'
    vm.bandForm.inputAmount = 1000
    vm.bandForm.taxCode = 'JP_STANDARD_10'

    await vm.createPriceRevision()

    const payload = createPriceRevision.mock.calls[0]![0] as { effectiveUntil: string | null }
    expect(payload.effectiveUntil).toMatch(OFFSET_ISO)
    expect(payload.effectiveUntil!.startsWith('2026-12-31T23:30')).toBe(true)
  })

  it('税コード登録の validFrom もオフセット付き ISO-8601 で送信される', async () => {
    const wrapper = await mountPage()
    const vm = wrapper.vm as unknown as PageVm
    vm.taxCodeForm.code = 'JP_TEST_5'
    vm.taxCodeForm.displayName = 'テスト税率5%'
    vm.taxCodeForm.validFrom = '2026-10-01T00:00'

    await vm.submitCreateTaxCode()

    expect(createTaxCode).toHaveBeenCalledTimes(1)
    const payload = createTaxCode.mock.calls[0]![0] as { validFrom: string }
    expect(payload.validFrom).toMatch(OFFSET_ISO)
    expect(payload.validFrom.startsWith('2026-10-01T00:00')).toBe(true)
  })
})
