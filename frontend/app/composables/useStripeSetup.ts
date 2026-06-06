import { loadStripe } from '@stripe/stripe-js'
import type {
  Stripe,
  StripeElements,
  StripePaymentElement,
  SetupIntent,
  StripeError,
} from '@stripe/stripe-js'

/**
 * F08.9 P5 継続課金: Stripe.js のクライアント統合ヘルパー。
 *
 * 会費加入フロー（設計書 04_ui_i18n.md §2.2 / 02_api_design.md §4.1）:
 *   1. BE: POST /api/v1/me/payment-methods/setup-intent → clientSecret 取得
 *   2. 本 composable: PaymentElement をマウントしカード情報を収集
 *   3. confirmSetup（redirect:'if_required'）で SetupIntent を確定し PaymentMethod を得る
 *      - 3DS が必要なカードはブラウザリダイレクトで認証ページへ遷移する
 *   4. BE: POST /confirm（PaymentMethod を Customer へ attach＋既定設定）
 *   5. BE: POST /payment-items/{itemId}/subscribe
 *
 * 設計原則:
 *   - any 禁止（@stripe/stripe-js の公式型のみ使用）。
 *   - エラーは握り潰さず型付き結果（StripeSetupResult）で返す（根治治療原則）。
 *   - publishableKey 未設定は明示エラー（症状を隠さない）。
 *   - client_secret は localStorage/sessionStorage に保存しない・ログに出さない。
 */

/** confirmSetup の戻り値。成功・エラー・リダイレクト（戻り値なし）の 3 分岐を型で表現する。 */
export type StripeSetupResult =
  | { status: 'succeeded'; paymentMethodId: string }
  | { status: 'error'; message: string }

/** retrieveSetupIntent の戻り値（3DS 復帰時に状態を確認する）。 */
export type RetrieveSetupIntentResult =
  | { status: 'ok'; setupIntent: SetupIntent }
  | { status: 'error'; message: string }

export function useStripeSetup() {
  const config = useRuntimeConfig()
  const { t } = useI18n()

  // loadStripe の遅延シングルトン。複数回マウントしても Stripe.js は 1 度だけロードする。
  let stripePromise: Promise<Stripe | null> | null = null

  /**
   * Stripe インスタンスを取得する（遅延シングルトン）。
   * publishableKey 未設定の場合は明示的に例外を投げる（症状を隠さない）。
   */
  async function getStripe(): Promise<Stripe> {
    const publishableKey = config.public.stripePublishableKey
    if (!publishableKey) {
      throw new Error(t('payment.membership.subscribe.keyMissing'))
    }
    if (!stripePromise) {
      stripePromise = loadStripe(publishableKey)
    }
    const stripe = await stripePromise
    if (!stripe) {
      // loadStripe が null を返す = スクリプトロード失敗。再試行できるよう singleton を破棄する。
      stripePromise = null
      throw new Error(t('payment.membership.subscribe.loadFailed'))
    }
    return stripe
  }

  /**
   * clientSecret から Elements を生成し、指定 DOM へ PaymentElement をマウントする。
   * 呼び出し側は戻り値の elements を confirmSetup に渡し、unmount で破棄すること。
   */
  async function mountPaymentElement(
    clientSecret: string,
    domId: string,
  ): Promise<{
    stripe: Stripe
    elements: StripeElements
    paymentElement: StripePaymentElement
    unmount: () => void
  }> {
    const stripe = await getStripe()
    const elements = stripe.elements({ clientSecret })
    const paymentElement = elements.create('payment')
    paymentElement.mount(`#${domId}`)
    const unmount = () => {
      // unmount → destroy の順で DOM とリスナを完全に解放する。
      paymentElement.unmount()
      paymentElement.destroy()
    }
    return { stripe, elements, paymentElement, unmount }
  }

  /**
   * SetupIntent を確定する（redirect:'if_required'）。
   *   - 成功（非リダイレクト）: { status:'succeeded', paymentMethodId } を返す。
   *   - 3DS 等のリダイレクト発生時: ブラウザが returnUrl へ遷移し本関数は解決しない（戻り値なし）。
   *   - エラー: Stripe の error.message を含む { status:'error' } を返す（握り潰さない）。
   */
  async function confirmSetup(params: {
    stripe: Stripe
    elements: StripeElements
    returnUrl: string
  }): Promise<StripeSetupResult> {
    const { stripe, elements, returnUrl } = params
    const result = await stripe.confirmSetup({
      elements,
      confirmParams: { return_url: returnUrl },
      redirect: 'if_required',
    })

    if (result.error) {
      return {
        status: 'error',
        message: result.error.message ?? t('payment.membership.subscribe.genericError'),
      }
    }

    const paymentMethodId = extractPaymentMethodId(result.setupIntent?.payment_method)
    if (!paymentMethodId) {
      // 成功なのに PaymentMethod が取れない = 想定外の状態。隠さず明示エラーにする。
      return { status: 'error', message: t('payment.membership.subscribe.noPaymentMethod') }
    }
    return { status: 'succeeded', paymentMethodId }
  }

  /**
   * 3DS リダイレクト復帰用に SetupIntent を取得する。
   * returnUrl に付与された setup_intent_client_secret から状態を確認する。
   */
  async function retrieveSetupIntent(clientSecret: string): Promise<RetrieveSetupIntentResult> {
    const stripe = await getStripe()
    const result = await stripe.retrieveSetupIntent(clientSecret)
    if (result.error) {
      return {
        status: 'error',
        message: result.error.message ?? t('payment.membership.subscribe.genericError'),
      }
    }
    if (!result.setupIntent) {
      return { status: 'error', message: t('payment.membership.subscribe.genericError') }
    }
    return { status: 'ok', setupIntent: result.setupIntent }
  }

  return {
    getStripe,
    mountPaymentElement,
    confirmSetup,
    retrieveSetupIntent,
  }
}

/**
 * SetupIntent.payment_method（string | null | PaymentMethod オブジェクト）から ID 文字列を抽出する。
 * confirm 後は通常 string で返るが、expand 時はオブジェクトのため両対応する。
 */
function extractPaymentMethodId(
  paymentMethod: SetupIntent['payment_method'] | undefined,
): string | null {
  if (!paymentMethod) {
    return null
  }
  if (typeof paymentMethod === 'string') {
    return paymentMethod
  }
  return paymentMethod.id ?? null
}

// StripeError 型を re-export し、呼び出し側がエラー型を参照できるようにする。
export type { StripeError }
