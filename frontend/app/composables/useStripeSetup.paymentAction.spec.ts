import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useStripeSetup } from './useStripeSetup'

/**
 * Billing Center PR6b-1 — AC-73 / AC-59（FE 側）の試練（red）。
 *
 * <p>AC-73: プラン変更の 3DS は「BE から受け取った clientSecret <b>だけ</b>」で発火できねばならない。
 * 既存の {@link useStripeSetup} の `confirmPayment` は<b>マウント済みの `elements`</b> を要求するため
 * そのままでは使えない（PaymentElement を出す画面が無い）。第12隊は
 * `confirmPaymentAction({ clientSecret, returnUrl })` を足し、内部では
 * `stripe.handleNextAction({ clientSecret })`（または `confirmPayment` の
 * `clientSecret` 指定形）を用いること。</p>
 *
 * <p>AC-59: clientSecret を localStorage / sessionStorage へ書かない。</p>
 */

// useRuntimeConfig は Nuxt 内部（router プラグイン等）も利用するため、stripePublishableKey
// 以外のフィールド（app.baseURL 等）も保持した完全な形で返す。不完全な戻り値だと
// setupNuxt 内の useRouter().afterEach が undefined を踏んで環境全体が失敗する
// （tests/unit/composables/useStripeSetup.spec.ts の既存コメント・実装と同じ理由・同じ形。
// 実測: 不完全な戻り値のままだと本ファイルは 3 件とも
// `Cannot read properties of undefined (reading 'afterEach')` で毎回失敗する）。
mockNuxtImport('useRuntimeConfig', () => () => ({
  app: { baseURL: '/', buildId: 'test', buildAssetsDir: '/_nuxt/', cdnURL: '' },
  public: {
    stripePublishableKey: 'pk_test_dummy',
    i18n: { routesNameSeparator: '___', defaultLocaleRouteNameSuffix: 'default' },
  },
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

const handleNextAction = vi.fn()
const confirmPayment = vi.fn()
const stripeInstance = { handleNextAction, confirmPayment, elements: vi.fn() }

vi.mock('@stripe/stripe-js', () => ({
  loadStripe: () => Promise.resolve(stripeInstance),
}))

const CLIENT_SECRET = 'pi_3DSsecret_test_secret_abc123XYZ'

beforeEach(() => {
  handleNextAction.mockReset()
  confirmPayment.mockReset()
  localStorage.clear()
  sessionStorage.clear()
})

describe('useStripeSetup — プラン変更の 3DS（AC-73 / AC-59）', () => {
  it('AC-73: clientSecret だけで 3DS を発火できる（マウント済み elements を要求しない）', async () => {
    handleNextAction.mockResolvedValueOnce({ paymentIntent: { status: 'succeeded' } })

    const setup = useStripeSetup() as unknown as {
      confirmPaymentAction?: (params: { clientSecret: string, returnUrl: string }) => Promise<unknown>
    }
    expect(
      typeof setup.confirmPaymentAction,
      'clientSecret だけで発火できる関数が無い（既存 confirmPayment は elements を要求する）',
    ).toBe('function')

    const result = await setup.confirmPaymentAction!({
      clientSecret: CLIENT_SECRET,
      returnUrl: 'https://example.test/billing/payment-action/return',
    })

    expect(handleNextAction).toHaveBeenCalledTimes(1)
    expect(handleNextAction.mock.calls[0]![0]).toMatchObject({ clientSecret: CLIENT_SECRET })
    expect(result).toEqual({ status: 'succeeded', paymentIntentStatus: 'succeeded' })
  })

  it('AC-73: Stripe のエラーは握り潰さず型付きで返す', async () => {
    handleNextAction.mockResolvedValueOnce({ error: { message: 'card_declined' } })

    const setup = useStripeSetup() as unknown as {
      confirmPaymentAction?: (params: { clientSecret: string, returnUrl: string }) => Promise<unknown>
    }
    const result = await setup.confirmPaymentAction!({
      clientSecret: CLIENT_SECRET,
      returnUrl: 'https://example.test/billing/payment-action/return',
    })

    expect(result).toEqual({ status: 'error', message: 'card_declined' })
  })

  it('AC-59: clientSecret を localStorage / sessionStorage へ書かない', async () => {
    handleNextAction.mockResolvedValueOnce({ paymentIntent: { status: 'succeeded' } })

    const setup = useStripeSetup() as unknown as {
      confirmPaymentAction?: (params: { clientSecret: string, returnUrl: string }) => Promise<unknown>
    }
    await setup.confirmPaymentAction!({
      clientSecret: CLIENT_SECRET,
      returnUrl: 'https://example.test/billing/payment-action/return',
    })

    // 陽性対照: storage 自体は生きている（常に空だから通る、という緑にしない）。
    localStorage.setItem('probe', 'alive')
    expect(localStorage.getItem('probe')).toBe('alive')

    const dump = [
      ...Object.keys(localStorage).map(k => `${k}=${localStorage.getItem(k)}`),
      ...Object.keys(sessionStorage).map(k => `${k}=${sessionStorage.getItem(k)}`),
    ].join('\n')
    expect(dump).not.toContain(CLIENT_SECRET)
  })
})
