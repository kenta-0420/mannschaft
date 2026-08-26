import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * F08.9 P5 useStripeSetup ユニットテスト。
 *
 * 検証観点:
 *   STRIPE-001: publishableKey 未設定なら getStripe が keyMissing エラーを投げる
 *   STRIPE-002: loadStripe が呼ばれ、複数回でもシングルトン（1 度だけ）
 *   STRIPE-003: loadStripe が null を返すと loadFailed エラー（症状を隠さない）
 *   STRIPE-004: confirmSetup 成功 → { status:'succeeded', paymentMethodId }
 *   STRIPE-005: confirmSetup が PaymentMethod オブジェクトを返しても id を抽出する
 *   STRIPE-006: confirmSetup エラー → { status:'error', message }（error.message を含む）
 *   STRIPE-007: confirmSetup でリダイレクト（error なし・setupIntent なし）→ noPaymentMethod エラー
 *   STRIPE-008: retrieveSetupIntent 成功 → { status:'ok', setupIntent }
 *   STRIPE-009: retrieveSetupIntent エラー → { status:'error', message }
 */

// ============================================================
// @stripe/stripe-js の loadStripe をモック
// ============================================================
const confirmSetupMock = vi.fn()
const retrieveSetupIntentMock = vi.fn()
const elementsMock = vi.fn()
const stripeInstance = {
  confirmSetup: confirmSetupMock,
  retrieveSetupIntent: retrieveSetupIntentMock,
  elements: elementsMock,
}
const loadStripeMock = vi.fn()

vi.mock('@stripe/stripe-js', () => ({
  loadStripe: (...args: unknown[]) => loadStripeMock(...args),
}))

// ============================================================
// Nuxt auto-import のモック（useRuntimeConfig / useI18n）
// publishableKey は各テストで差し替えるため可変参照にする。
// ============================================================
let publishableKey = 'pk_test_dummy'

// useRuntimeConfig は Nuxt 内部（router プラグイン等）も利用するため、
// stripePublishableKey 以外のフィールド（app.baseURL 等）も保持した完全な形で返す。
// 不完全な戻り値だと setupNuxt 内の useRouter().afterEach が undefined を踏んで失敗する。
mockNuxtImport('useRuntimeConfig', () => () => ({
  app: { baseURL: '/', buildId: 'test', buildAssetsDir: '/_nuxt/', cdnURL: '' },
  public: {
    stripePublishableKey: publishableKey,
    // @nuxtjs/i18n プラグインが app 初期化時に参照するため最小フィールドを与える。
    i18n: { routesNameSeparator: '___', defaultLocaleRouteNameSuffix: 'default' },
  },
}))

// t() はキーをそのまま返し、メッセージにキーが含まれることを検証可能にする。
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

// eslint-disable-next-line import/first
import { useStripeSetup } from '~/composables/useStripeSetup'

describe('useStripeSetup', () => {
  beforeEach(() => {
    publishableKey = 'pk_test_dummy'
    loadStripeMock.mockReset()
    confirmSetupMock.mockReset()
    retrieveSetupIntentMock.mockReset()
    elementsMock.mockReset()
    loadStripeMock.mockResolvedValue(stripeInstance)
  })

  it('STRIPE-001: publishableKey 未設定なら keyMissing エラーを投げる', async () => {
    publishableKey = ''
    const { getStripe } = useStripeSetup()
    await expect(getStripe()).rejects.toThrow('payment.membership.subscribe.keyMissing')
    expect(loadStripeMock).not.toHaveBeenCalled()
  })

  it('STRIPE-002: loadStripe は publishableKey 付きで呼ばれ、複数回でもシングルトン', async () => {
    const { getStripe } = useStripeSetup()
    const a = await getStripe()
    const b = await getStripe()
    expect(a).toBe(stripeInstance)
    expect(b).toBe(stripeInstance)
    expect(loadStripeMock).toHaveBeenCalledTimes(1)
    expect(loadStripeMock).toHaveBeenCalledWith('pk_test_dummy')
  })

  it('STRIPE-003: loadStripe が null を返すと loadFailed エラー', async () => {
    loadStripeMock.mockResolvedValue(null)
    const { getStripe } = useStripeSetup()
    await expect(getStripe()).rejects.toThrow('payment.membership.subscribe.loadFailed')
  })

  it('STRIPE-004: confirmSetup 成功で paymentMethodId を返す（string）', async () => {
    confirmSetupMock.mockResolvedValue({
      setupIntent: { payment_method: 'pm_123', status: 'succeeded' },
    })
    const { confirmSetup } = useStripeSetup()
    const elements = {} as never
    const result = await confirmSetup({
      stripe: stripeInstance as never,
      elements,
      returnUrl: 'https://example.com/return',
    })
    expect(result).toEqual({ status: 'succeeded', paymentMethodId: 'pm_123' })
    expect(confirmSetupMock).toHaveBeenCalledWith({
      elements,
      confirmParams: { return_url: 'https://example.com/return' },
      redirect: 'if_required',
    })
  })

  it('STRIPE-005: payment_method がオブジェクトでも id を抽出する', async () => {
    confirmSetupMock.mockResolvedValue({
      setupIntent: { payment_method: { id: 'pm_obj' } },
    })
    const { confirmSetup } = useStripeSetup()
    const result = await confirmSetup({
      stripe: stripeInstance as never,
      elements: {} as never,
      returnUrl: 'https://example.com/return',
    })
    expect(result).toEqual({ status: 'succeeded', paymentMethodId: 'pm_obj' })
  })

  it('STRIPE-006: confirmSetup エラーは error.message を含む型付き結果で返す', async () => {
    confirmSetupMock.mockResolvedValue({
      error: { message: 'Your card was declined.' },
    })
    const { confirmSetup } = useStripeSetup()
    const result = await confirmSetup({
      stripe: stripeInstance as never,
      elements: {} as never,
      returnUrl: 'https://example.com/return',
    })
    expect(result).toEqual({ status: 'error', message: 'Your card was declined.' })
  })

  it('STRIPE-007: error なし・setupIntent なし（リダイレクト系）は noPaymentMethod エラー', async () => {
    confirmSetupMock.mockResolvedValue({})
    const { confirmSetup } = useStripeSetup()
    const result = await confirmSetup({
      stripe: stripeInstance as never,
      elements: {} as never,
      returnUrl: 'https://example.com/return',
    })
    expect(result).toEqual({
      status: 'error',
      message: 'payment.membership.subscribe.noPaymentMethod',
    })
  })

  it('STRIPE-008: retrieveSetupIntent 成功で setupIntent を返す', async () => {
    const setupIntent = { id: 'seti_1', status: 'succeeded' }
    retrieveSetupIntentMock.mockResolvedValue({ setupIntent })
    const { retrieveSetupIntent } = useStripeSetup()
    const result = await retrieveSetupIntent('seti_secret')
    expect(result).toEqual({ status: 'ok', setupIntent })
    expect(retrieveSetupIntentMock).toHaveBeenCalledWith('seti_secret')
  })

  it('STRIPE-009: retrieveSetupIntent エラーは error.message を返す', async () => {
    retrieveSetupIntentMock.mockResolvedValue({
      error: { message: 'No such setup intent' },
    })
    const { retrieveSetupIntent } = useStripeSetup()
    const result = await retrieveSetupIntent('seti_secret')
    expect(result).toEqual({ status: 'error', message: 'No such setup intent' })
  })
})
