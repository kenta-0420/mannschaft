<script setup lang="ts">
/**
 * F08.9 P5 継続課金: Stripe PaymentElement 決済フォーム（第一波の決済部品）。
 *
 * 設計書: docs/features/F08.9_membership_billing_paywall/04_ui_i18n.md §2.2
 *
 * 役割:
 *   - 親から受け取った clientSecret で Stripe PaymentElement をマウントする。
 *   - 送信時に confirmSetup（redirect:'if_required'）で PaymentMethod を確定し、
 *     成功時は paymentMethodId を success emit する（親が /confirm→subscribe を呼ぶ）。
 *   - 3DS が必要なカードは confirmSetup がブラウザを returnUrl へ遷移させる（emit なし）。
 *   - エラーは握り潰さず error emit ＋画面表示する（Stripe 提供文言を i18n 経由で表示）。
 *
 * セキュリティ:
 *   - clientSecret は props 受け渡しのみ。localStorage/sessionStorage に保存しない。
 *   - clientSecret / カード情報を console.log に出さない。
 *
 * 親への契約:
 *   - props.clientSecret: BE SetupIntent の clientSecret（必須）。
 *   - props.returnUrl: 3DS リダイレクト後の復帰先 URL（必須）。
 *   - emit success(paymentMethodId: string): PaymentMethod 確定成功（非リダイレクト時）。
 *   - emit error(message: string): 失敗・読み込みエラー。
 */
import type { StripeElements, Stripe } from '@stripe/stripe-js'

interface Props {
  clientSecret: string
  returnUrl: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  success: [paymentMethodId: string]
  error: [message: string]
}>()

const { t } = useI18n()
const { mountPaymentElement, confirmSetup } = useStripeSetup()

/** PaymentElement のマウント先 DOM の一意 ID（複数フォーム共存時の衝突回避）。 */
const elementDomId = `stripe-payment-element-${useId()}`

const submitting = ref(false)
const loaded = ref(false)
const formError = ref<string | null>(null)

let stripe: Stripe | null = null
let elements: StripeElements | null = null
let unmountElement: (() => void) | null = null

onMounted(async () => {
  try {
    const mounted = await mountPaymentElement(props.clientSecret, elementDomId)
    stripe = mounted.stripe
    elements = mounted.elements
    unmountElement = mounted.unmount
    loaded.value = true
  } catch (e: unknown) {
    // 読み込み失敗は隠さず error emit ＋画面表示する。
    const message = e instanceof Error ? e.message : t('payment.membership.subscribe.loadFailed')
    formError.value = message
    emit('error', message)
  }
})

onUnmounted(() => {
  // PaymentElement を破棄し DOM/リスナを解放する。
  unmountElement?.()
  unmountElement = null
  elements = null
  stripe = null
})

async function onSubmit() {
  // 二重送信防止＋未マウント時のガード。
  if (submitting.value || !stripe || !elements) {
    return
  }
  submitting.value = true
  formError.value = null

  try {
    const result = await confirmSetup({ stripe, elements, returnUrl: props.returnUrl })
    if (result.status === 'succeeded') {
      emit('success', result.paymentMethodId)
    } else {
      // result.message は Stripe 提供文言（i18n フォールバック込み）。
      formError.value = result.message
      emit('error', result.message)
    }
  } finally {
    // リダイレクト時はこの行に到達しない（ブラウザ遷移済み）。
    submitting.value = false
  }
}
</script>

<template>
  <form class="stripe-payment-form" @submit.prevent="onSubmit">
    <label class="stripe-payment-form__label" :for="elementDomId">
      {{ t('payment.membership.subscribe.cardLabel') }}
    </label>
    <!-- Stripe PaymentElement のマウント先。中身は Stripe の iframe が描画する。 -->
    <div :id="elementDomId" class="stripe-payment-form__element" />

    <p v-if="formError" class="stripe-payment-form__error" role="alert">
      {{ formError }}
    </p>

    <button
      type="submit"
      class="stripe-payment-form__submit"
      :disabled="submitting || !loaded"
    >
      {{ submitting
        ? t('payment.membership.subscribe.processing')
        : t('payment.membership.subscribe.submit') }}
    </button>
  </form>
</template>

<style scoped>
.stripe-payment-form {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.stripe-payment-form__label {
  font-weight: 500;
}

.stripe-payment-form__error {
  color: var(--p-red-600, #dc2626);
  font-size: 0.875rem;
}

.stripe-payment-form__submit {
  padding: 0.625rem 1rem;
  border-radius: 0.5rem;
  background-color: var(--p-primary-color, #3b82f6);
  color: #fff;
  font-weight: 600;
  cursor: pointer;
}

.stripe-payment-form__submit:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
