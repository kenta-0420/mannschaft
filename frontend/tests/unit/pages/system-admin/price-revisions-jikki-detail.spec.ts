import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, h, nextTick } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { createJaLocaleT } from '../../helpers/localeJson'

/**
 * 価格改定 詳細画面 — 実機E2E（2026-09-29）で見つかった欠陥の検体。
 *
 * - 欠陥2: 取り消しが失敗したとき、未知のコード（COMMON_999 など）まで「現在の状態ではこの操作を実行できません」
 *   に振り分けていた。未知のコードは共通のエラー処理（5xx は useApi の共通トーストのみ・それ以外は
 *   handleApiError）へ委ね、状態衝突の文言は既知のコード（PRICE_REVISION_018）だけに使う。
 * - 欠陥5: API は band ごとに stripePriceRef を返しているのに画面に出ていなかった。
 * - 欠陥6: 権限なしで URL を直打ちすると、権限なし表示なのに詳細 API を呼んで 403 を受け、
 *   「読み込みに失敗しました」のトーストまで出ていた。
 */

const getPriceRevision = vi.fn()
const cancelPriceRevision = vi.fn()
const notifySuccess = vi.fn()
const notifyError = vi.fn()
const handleApiError = vi.fn()
const authState = { isSystemAdmin: true }

mockNuxtImport('useBillingApi', () => () => ({
  getPriceRevision,
  cancelPriceRevision,
  reconcileProvisionPriceRevision: vi.fn(),
  provisionPriceRevision: vi.fn(),
  retryProvisionPriceRevision: vi.fn(),
  activatePriceRevision: vi.fn(),
}))
mockNuxtImport('useAuthStore', () => () => ({
  get isSystemAdmin() { return authState.isSystemAdmin },
  user: { timezone: 'Asia/Tokyo' },
  loadFromStorage: vi.fn(),
}))
mockNuxtImport('useRoute', () => () => ({ params: { id: 'rev-1' }, query: {}, path: '/system-admin/price-revisions/rev-1' }))
mockNuxtImport('useNotification', () => () => ({ success: notifySuccess, error: notifyError }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))

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

let confirmAcceptCallback: (() => void | Promise<void>) | null = null
mockNuxtImport('useConfirm', () => () => ({
  require: (opts: { accept: () => void | Promise<void> }) => { confirmAcceptCallback = opts.accept },
  close: vi.fn(),
}))

const Page = (await import('~/pages/system-admin/price-revisions/[id].vue')).default

function revision(status: string, stripePriceRef: string | null = null) {
  return {
    id: 'rev-1', productKind: 'PLAN', productKey: 'FULL', scopeKind: 'TEAM', revisionNo: 1,
    catalogRevision: 'REV-1', status, lockVersion: 3, effectiveFrom: '2026-10-01T00:00:00Z',
    effectiveUntil: null,
    bands: [{ id: 'b1', bandNo: 1, minMembers: 1, maxMembers: null, inputAmount: 1000,
      taxBehavior: 'EXCLUSIVE', taxCode: 'JP_STANDARD_10', amountExcludingTax: 1000, taxAmount: 100,
      amountIncludingTax: 1100, taxRateBasisPoints: 1000, status, stripePriceRef, provisionAttempts: 1 }],
  }
}

const ButtonStub = defineComponent({
  inheritAttrs: false,
  props: { label: { type: String, default: '' } },
  setup(props, { attrs }) {
    return () => h('button', { ...attrs }, props.label)
  },
})

async function mountPage(rev: ReturnType<typeof revision>) {
  getPriceRevision.mockResolvedValue({ data: rev })
  const wrapper = await mountSuspended(Page, { global: { stubs: { Button: ButtonStub, Tag: true } } })
  await flushPromises()
  await nextTick()
  return wrapper
}

async function cancelWith(error: unknown) {
  cancelPriceRevision.mockRejectedValue(error)
  const wrapper = await mountPage(revision('DRAFT'))
  await wrapper.find('button[aria-label="cancel-price-revision"]').trigger('click')
  await flushPromises()
  await confirmAcceptCallback!()
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  authState.isSystemAdmin = true
  getPriceRevision.mockReset()
  cancelPriceRevision.mockReset()
  notifySuccess.mockReset()
  notifyError.mockReset()
  handleApiError.mockReset()
  confirmAcceptCallback = null
})

describe('欠陥2: 取り消し失敗時の文言', () => {
  it('500 COMMON_999 は状態衝突の文言にせず、ページ側ではトーストを重ねない（useApi の共通5xxトーストに任せる）', async () => {
    await cancelWith({ statusCode: 500, data: { error: { code: 'COMMON_999', message: 'Internal' } } })

    expect(notifyError).not.toHaveBeenCalled()
    expect(handleApiError).not.toHaveBeenCalled()
  })

  it('4xx の未知コードは状態衝突の文言にせず、handleApiError（共通のエラー処理）へ委ねる', async () => {
    const error = { statusCode: 400, data: { error: { code: 'COMMON_001', message: '入力が不正です' } } }
    await cancelWith(error)

    expect(notifyError).not.toHaveBeenCalled()
    expect(handleApiError).toHaveBeenCalledWith(error, 'price-revisions-cancel')
  })

  it('409 PRICE_REVISION_018（状態の衝突）だけは状態衝突の文言を出す', async () => {
    await cancelWith({ statusCode: 409, data: { error: { code: 'PRICE_REVISION_018', message: 'state' } } })

    expect(notifyError).toHaveBeenCalledTimes(1)
    expect(notifyError.mock.calls[0]![0]).toBe('現在の状態ではこの操作を実行できません')
    expect(handleApiError).not.toHaveBeenCalled()
  })

  it('band の未知のエラーコードは状態衝突ではなく汎用の失敗文言で表示する', async () => {
    const rev = revision('PROVISION_FAILED')
    ;(rev.bands[0] as Record<string, unknown>).provisionErrorCode = 'STRIPE_API_ERROR'
    const wrapper = await mountPage(rev)

    const text = wrapper.find('[aria-label="band-error-1"]').text()
    expect(text).not.toBe('現在の状態ではこの操作を実行できません')
    expect(text).toBe('操作に失敗しました。時間をおいて再度お試しください')
  })
})

describe('欠陥5: band ごとの Stripe Price ID 表示', () => {
  it('stripePriceRef を返す band は Price ID を表示する', async () => {
    const wrapper = await mountPage(revision('READY', 'price_1AbCdEf'))

    expect(wrapper.find('[aria-label="band-stripe-price-ref-1"]').text()).toBe('price_1AbCdEf')
  })

  it('stripePriceRef が無い band は「未作成」を表示する', async () => {
    const wrapper = await mountPage(revision('DRAFT', null))

    expect(wrapper.find('[aria-label="band-stripe-price-ref-1"]').text()).toBe('未作成')
  })
})

describe('欠陥6: 権限なしの直打ち', () => {
  it('SYSTEM_ADMIN でなければ詳細 API を呼ばず、読み込み失敗のトーストも出さない', async () => {
    authState.isSystemAdmin = false
    getPriceRevision.mockRejectedValue({ statusCode: 403 })
    const wrapper = await mountSuspended(Page, { global: { stubs: { Button: ButtonStub, Tag: true } } })
    await flushPromises()

    expect(wrapper.text()).toContain('この画面を表示する権限がありません')
    expect(getPriceRevision).not.toHaveBeenCalled()
    expect(notifyError).not.toHaveBeenCalled()
  })
})
