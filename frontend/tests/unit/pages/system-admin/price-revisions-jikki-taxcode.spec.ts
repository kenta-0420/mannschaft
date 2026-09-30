import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, h, nextTick } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { createJaLocaleT } from '../../helpers/localeJson'

/**
 * 価格改定 一覧画面（税コードマスタ）— 実機E2E（2026-09-29）で見つかった欠陥の検体。
 *
 * - 欠陥4: 税コード登録ダイアログに Stripe 税コード（stripeTaxCode）の入力欄が無く、常に空文字で送られて
 *   null で保存されていた。入力欄を足し、BE（PRICE_REVISION_021）と同じ `^txcd_\d{8}$` を FE でも検証する。
 *   BE は null を許す（Product に tax_code を付けない）ので任意項目とし、空欄は null で送る。
 *   一覧にも Stripe 税コードを表示し、displayName 欄の placeholder が「税コード」だった誤りも直す。
 * - 欠陥6（一覧側）: 権限なしで一覧を直打ちしたとき、一覧 API を呼んで 403 のトーストを出さない。
 */

const listPriceRevisions = vi.fn()
const listTaxCodes = vi.fn()
const createTaxCode = vi.fn()
const notifySuccess = vi.fn()
const notifyError = vi.fn()
const authState = { isSystemAdmin: true }

mockNuxtImport('useBillingApi', () => () => ({
  listPriceRevisions,
  listTaxCodes,
  createTaxCode,
  updateTaxCode: vi.fn(),
  deactivateTaxCode: vi.fn(),
  createPriceRevision: vi.fn(),
}))
mockNuxtImport('useAuthStore', () => () => ({
  get isSystemAdmin() { return authState.isSystemAdmin },
  user: { timezone: 'Asia/Tokyo' },
  loadFromStorage: vi.fn(),
}))
mockNuxtImport('useNotification', () => () => ({ success: notifySuccess, error: notifyError }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))

/**
 * テスト環境（jsdom）は navigator.language が既定で 'en-US' になり、@nuxtjs/i18n の
 * detectBrowserLanguage がこれを拾って defaultLocale('ja') より優先してしまう
 * （本番はブラウザの Accept-Language/Cookie を見る正しい挙動なのでバグではないが、
 * ユニットテストでは日本語文言の一致を検証したいので `t` を固定する）。
 *
 * `t` は手書き辞書ではなく `app/locales/ja/billing.json` を実際に読んで解決する
 * （キーが locale.json から消えたら throw で赤化させ、モックがコードを追認しないようにする）。
 */
const jaT = createJaLocaleT(['billing'])
mockNuxtImport('useI18n', () => () => ({ t: jaT }))

const Page = (await import('~/pages/system-admin/price-revisions/index.vue')).default

const ButtonStub = defineComponent({
  inheritAttrs: false,
  props: { label: { type: String, default: '' }, disabled: { type: Boolean, default: false } },
  setup(props, { attrs }) {
    return () => h('button', { ...attrs, disabled: props.disabled }, props.label)
  },
})
const DialogStub = defineComponent({
  props: { visible: { type: Boolean, default: false } },
  setup(props, { slots }) {
    return () => (props.visible ? h('div', { class: 'dialog-stub' }, [slots.default?.(), slots.footer?.()]) : null)
  },
})
const InputTextStub = defineComponent({
  inheritAttrs: false,
  props: { modelValue: { type: String, default: '' }, placeholder: { type: String, default: '' } },
  emits: ['update:modelValue'],
  setup(props, { attrs, emit }) {
    return () => h('input', {
      ...attrs,
      value: props.modelValue,
      placeholder: props.placeholder,
      onInput: (e: Event) => emit('update:modelValue', (e.target as HTMLInputElement).value),
    })
  },
})

const stubs = {
  Button: ButtonStub, Dialog: DialogStub, InputText: InputTextStub,
  InputNumber: true, Dropdown: true, Tag: true, Column: true, DataTable: true,
}

async function openTaxCodeDialog() {
  const wrapper = await mountSuspended(Page, { global: { stubs } })
  await flushPromises()
  await wrapper.find('button[aria-label="tax-codes"]').trigger('click')
  await flushPromises()
  await nextTick()
  return wrapper
}

async function fillRequired(wrapper: Awaited<ReturnType<typeof openTaxCodeDialog>>) {
  await wrapper.find('input[aria-label="new-tax-code-code"]').setValue('JP_TEST_10')
  await wrapper.find('input[aria-label="new-tax-code-display-name"]').setValue('テスト10%')
  await wrapper.find('input[aria-label="new-tax-code-valid-from"]').setValue('2026-10-01T00:00')
}

beforeEach(() => {
  authState.isSystemAdmin = true
  listPriceRevisions.mockReset().mockResolvedValue({ data: { items: [], totalElements: 0 } })
  listTaxCodes.mockReset().mockResolvedValue([])
  createTaxCode.mockReset().mockResolvedValue({})
  notifySuccess.mockReset()
  notifyError.mockReset()
})

describe('欠陥4: Stripe 税コードの入力欄', () => {
  it('Stripe 税コードの入力欄があり、displayName 欄の placeholder は「税コード」ではない', async () => {
    const wrapper = await openTaxCodeDialog()

    expect(wrapper.find('input[aria-label="new-tax-code-stripe-tax-code"]').exists()).toBe(true)
    expect(wrapper.find('input[aria-label="new-tax-code-display-name"]').attributes('placeholder')).not.toBe('税コード')
  })

  it('形式違反（txcd_ + 数字8桁でない）は BE と同じ文言を出し、登録ボタンを押せない', async () => {
    const wrapper = await openTaxCodeDialog()
    await fillRequired(wrapper)
    await wrapper.find('input[aria-label="new-tax-code-stripe-tax-code"]').setValue('txcd_123')

    expect(wrapper.find('[aria-label="stripe-tax-code-format-error"]').text())
      .toBe('Stripe税コードの形式が不正です（txcd_ に続く数字8桁）')
    expect(wrapper.find('button[aria-label="submit-create-tax-code"]').attributes('disabled')).toBeDefined()
  })

  it('正しい形式なら入力した Stripe 税コードを送る', async () => {
    const wrapper = await openTaxCodeDialog()
    await fillRequired(wrapper)
    await wrapper.find('input[aria-label="new-tax-code-stripe-tax-code"]').setValue('txcd_10000000')
    await wrapper.find('button[aria-label="submit-create-tax-code"]').trigger('click')
    await flushPromises()

    expect(createTaxCode).toHaveBeenCalledTimes(1)
    expect(createTaxCode.mock.calls[0]![0]).toMatchObject({ code: 'JP_TEST_10', stripeTaxCode: 'txcd_10000000' })
  })

  it('空欄は任意項目として null で送る（空文字を送らない）', async () => {
    const wrapper = await openTaxCodeDialog()
    await fillRequired(wrapper)
    await wrapper.find('button[aria-label="submit-create-tax-code"]').trigger('click')
    await flushPromises()

    expect(createTaxCode).toHaveBeenCalledTimes(1)
    expect(createTaxCode.mock.calls[0]![0].stripeTaxCode).toBeNull()
  })
})

describe('欠陥6（一覧）: 権限なしの直打ち', () => {
  it('SYSTEM_ADMIN でなければ一覧 API を呼ばず、読み込み失敗のトーストも出さない', async () => {
    authState.isSystemAdmin = false
    listPriceRevisions.mockRejectedValue({ statusCode: 403 })
    const wrapper = await mountSuspended(Page, { global: { stubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('この画面を表示する権限がありません')
    expect(listPriceRevisions).not.toHaveBeenCalled()
    expect(notifyError).not.toHaveBeenCalled()
  })
})
