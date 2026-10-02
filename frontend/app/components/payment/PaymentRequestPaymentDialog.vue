<script setup lang="ts">
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import type { Stripe, StripeElements } from '@stripe/stripe-js'

interface Props {
  visible: boolean
  /** 支払い開始 API が返す一時的な client secret。呼び出し元は保存しない。 */
  clientSecret: string | null
  /** 3DS が必要な場合に Stripe が戻す URL。 */
  returnUrl: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:visible': [value: boolean]
  /** Stripe 確認後。PAID は webhook 確定後なので親が詳細を短期ポーリングする。 */
  confirmed: [paymentIntentStatus: string]
}>()

const { t } = useI18n()
const { mountPaymentElement, confirmPayment } = useStripeSetup()

const loading = ref(false)
const submitting = ref(false)
const errorMessage = ref<string | null>(null)
const elementMounted = ref(false)
const elementDomId = `payment-request-payment-element-${useId()}`

let stripe: Stripe | null = null
let elements: StripeElements | null = null
let unmountElement: (() => void) | null = null
let mountGeneration = 0

function teardownElement() {
  mountGeneration += 1
  unmountElement?.()
  unmountElement = null
  stripe = null
  elements = null
  elementMounted.value = false
}

async function mountElement(clientSecret: string) {
  teardownElement()
  const generation = ++mountGeneration
  loading.value = true
  errorMessage.value = null
  await nextTick()

  try {
    const mounted = await mountPaymentElement(clientSecret, elementDomId)
    if (generation !== mountGeneration || !props.visible || props.clientSecret !== clientSecret) {
      mounted.unmount()
      return
    }
    stripe = mounted.stripe
    elements = mounted.elements
    unmountElement = mounted.unmount
    elementMounted.value = true
  } catch (error: unknown) {
    if (generation === mountGeneration) {
      errorMessage.value = error instanceof Error ? error.message : t('payment.membership.paymentRequest.payError')
    }
  } finally {
    if (generation === mountGeneration) {
      loading.value = false
    }
  }
}

async function onConfirmPayment() {
  if (submitting.value || !stripe || !elements) {
    return
  }
  submitting.value = true
  errorMessage.value = null
  try {
    const result = await confirmPayment({ stripe, elements, returnUrl: props.returnUrl })
    if (result.status === 'succeeded') {
      emit('confirmed', result.paymentIntentStatus)
      emit('update:visible', false)
      return
    }
    errorMessage.value = result.message
  } finally {
    submitting.value = false
  }
}

watch(
  () => [props.visible, props.clientSecret] as const,
  ([visible, clientSecret]) => {
    teardownElement()
    errorMessage.value = null
    if (visible && clientSecret) {
      void mountElement(clientSecret)
    }
  },
  { immediate: true },
)

onUnmounted(teardownElement)
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    :draggable="false"
    :header="t('payment.membership.paymentRequest.pay')"
    :style="{ width: '32rem' }"
    :breakpoints="{ '640px': '92vw' }"
    @update:visible="(value: boolean) => emit('update:visible', value)"
  >
    <div v-if="loading" class="flex justify-center p-6">
      <LoadingBounce />
    </div>
    <div v-else class="flex flex-col gap-3">
      <div :id="elementDomId" data-testid="payment-request-payment-element" />
      <p v-if="errorMessage" class="text-sm text-red-600" role="alert">
        {{ errorMessage }}
      </p>
    </div>

    <template #footer>
      <Button
        :label="t('common.cancel')"
        severity="secondary"
        text
        :disabled="submitting"
        @click="emit('update:visible', false)"
      />
      <Button
        data-testid="payment-request-confirm-button"
        :label="submitting ? t('payment.membership.paymentRequest.processing') : t('payment.membership.paymentRequest.pay')"
        :loading="submitting"
        :disabled="!elementMounted || submitting"
        @click="onConfirmPayment"
      />
    </template>
  </Dialog>
</template>
