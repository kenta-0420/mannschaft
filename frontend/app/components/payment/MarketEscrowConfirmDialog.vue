<script setup lang="ts">
/**
 * F22.1 市の謝礼決済: 札主の決済確認ダイアログ（成立前のカード与信）。
 *
 * 設計（02 §1 行#8 / 04 §3.1・マスター裁可の7日失効表示）:
 *   札主（支払者本人）が応募者を成立させる前に、謝礼エスクローの clientSecret＋手数料内訳を取得し、
 *   Stripe.js（PaymentElement + confirmPayment / manual capture）でカード与信を確認する。
 *
 * BE 配線（recon 済み・実在 EP）:
 *   - GET /api/v1/payment/escrow/recruitment/{listingId}/{participantId}/payment-intent
 *     → RecruitmentPaymentResponse { clientSecret, escrowTransactionId, status,
 *        faceAmount, chargeAmount, applicationFeeAmount }（camelCase・円整数）
 *
 * status 出し分け:
 *   - PENDING_CONFIRMATION: clientSecret あり → PaymentElement で confirm（与信）。
 *   - AUTHORIZED 以降:       確認済み（再 confirm 不要）。
 *   - DEFERRED:              7日超 fallback（いまは与信せず完了時に即時払い）。
 *   - HELD:                  受取側 onboarding 未完了（PI 未作成）。
 *   - 404（escrow 未準備・成立リスナ @Async 遅延）: リトライ案内。
 *
 * 与信は約7日で失効するため、確認画面に「7日以内に最終認証が必要」を明示する（マスター裁可）。
 * 既存 useStripeSetup（mountPaymentElement / confirmPayment）と useMarketPaymentApi を再利用する。
 */
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import type { StripeElements, Stripe } from '@stripe/stripe-js'
import type { RecruitmentPaymentResponse } from '~/types/marketPayment'

interface Props {
  /** ダイアログ表示状態（v-model:visible）。 */
  visible: boolean
  /** 札 ID（escrow の source_id）。 */
  listingId: number
  /** 応募 ID（escrow の source_participant_id）。 */
  participantId: number
}

const props = defineProps<Props>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  /** 与信確認（または DEFERRED/AUTHORIZED で確認不要）が完了し、成立を続行してよい状態。 */
  confirmed: [escrowTransactionId: string]
}>()

const { t } = useI18n()
const marketPaymentApi = useMarketPaymentApi()
const { mountPaymentElement, confirmPayment } = useStripeSetup()

const loading = ref(false)
const view = ref<RecruitmentPaymentResponse | null>(null)
const errorMessage = ref<string | null>(null)
/** escrow 未準備（404）= リトライ案内を出す。 */
const notReady = ref(false)
const submitting = ref(false)

/** PaymentElement のマウント先 DOM の一意 ID。 */
const elementDomId = `escrow-payment-element-${useId()}`

let stripe: Stripe | null = null
let elements: StripeElements | null = null
let unmountElement: (() => void) | null = null
let elementMounted = false

/** clientSecret があり confirm が必要な状態か。 */
const needsConfirm = computed(
  () => view.value?.status === 'PENDING_CONFIRMATION' && !!view.value.clientSecret,
)
/** 既に与信確定済み（再 confirm 不要）。 */
const alreadyAuthorized = computed(
  () => view.value != null
    && view.value.status !== 'PENDING_CONFIRMATION'
    && view.value.status !== 'DEFERRED'
    && view.value.status !== 'HELD',
)
const isDeferred = computed(() => view.value?.status === 'DEFERRED')
const isHeld = computed(() => view.value?.status === 'HELD')

/** お支払い手数料（課金額 − 額面）。 */
const feeAmount = computed(() =>
  view.value ? Math.max(0, view.value.chargeAmount - view.value.faceAmount) : 0,
)

function yen(amount: number): string {
  return `¥${amount.toLocaleString()}`
}

function teardownElement() {
  unmountElement?.()
  unmountElement = null
  elements = null
  stripe = null
  elementMounted = false
}

async function load() {
  loading.value = true
  errorMessage.value = null
  notReady.value = false
  view.value = null
  teardownElement()
  try {
    const res = await marketPaymentApi.getRecruitmentPaymentIntent(
      props.listingId,
      props.participantId,
    )
    view.value = res.data
    // PENDING_CONFIRMATION かつ clientSecret あり → PaymentElement をマウント。
    if (view.value.status === 'PENDING_CONFIRMATION' && view.value.clientSecret) {
      await mountElement(view.value.clientSecret)
    }
  } catch (e: unknown) {
    // 404（escrow 未準備）はリトライ案内、それ以外は明示エラー（握り潰さない）。
    const status = extractHttpStatus(e)
    if (status === 404) {
      notReady.value = true
    } else {
      errorMessage.value
        = e instanceof Error ? e.message : t('market.payment.confirm.loadFailed')
    }
  } finally {
    loading.value = false
  }
}

async function mountElement(clientSecret: string) {
  // DOM 描画後にマウントするため nextTick を挟む。
  await nextTick()
  try {
    const mounted = await mountPaymentElement(clientSecret, elementDomId)
    stripe = mounted.stripe
    elements = mounted.elements
    unmountElement = mounted.unmount
    elementMounted = true
  } catch (e: unknown) {
    errorMessage.value
      = e instanceof Error ? e.message : t('market.payment.confirm.loadFailed')
  }
}

async function onConfirmPayment() {
  if (submitting.value || !stripe || !elements) {
    return
  }
  submitting.value = true
  errorMessage.value = null
  try {
    const returnUrl = window.location.href
    const result = await confirmPayment({ stripe, elements, returnUrl })
    if (result.status === 'succeeded') {
      emit('confirmed', view.value!.escrowTransactionId)
      close()
    } else {
      errorMessage.value = result.message
    }
  } finally {
    // 3DS リダイレクト時はこの行に到達しない。
    submitting.value = false
  }
}

/** DEFERRED / AUTHORIZED 等、確認不要で成立を続行する。 */
function onProceedWithoutConfirm() {
  if (view.value) {
    emit('confirmed', view.value.escrowTransactionId)
  }
  close()
}

function close() {
  emit('update:visible', false)
}

watch(
  () => props.visible,
  (v) => {
    if (v) {
      void load()
    } else {
      teardownElement()
    }
  },
)

onUnmounted(teardownElement)

/** ofetch/$fetch のエラーから HTTP ステータスを取り出す（型安全に）。 */
function extractHttpStatus(e: unknown): number | null {
  if (e && typeof e === 'object') {
    const rec = e as Record<string, unknown>
    const status = rec.statusCode ?? rec.status
    if (typeof status === 'number') {
      return status
    }
  }
  return null
}
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    :draggable="false"
    :header="t('market.payment.confirm.title')"
    :style="{ width: '32rem' }"
    :breakpoints="{ '640px': '92vw' }"
    @update:visible="(v: boolean) => emit('update:visible', v)"
  >
    <div v-if="loading" class="flex justify-center p-6">
      <LoadingBounce />
    </div>

    <!-- escrow 未準備（404）: リトライ案内 -->
    <div v-else-if="notReady" class="flex flex-col gap-3">
      <p class="text-sm text-surface-600">
        {{ t('market.payment.confirm.preparing') }}
      </p>
      <Button
        :label="t('market.payment.confirm.retry')"
        icon="pi pi-refresh"
        severity="secondary"
        class="self-start"
        @click="load"
      />
    </div>

    <div v-else-if="view" class="flex flex-col gap-4">
      <p class="text-sm text-surface-700">
        {{ t('market.payment.confirm.intro') }}
      </p>

      <!-- 手数料内訳 -->
      <div class="rounded border border-surface-200 p-3">
        <p class="mb-2 text-sm font-medium">
          {{ t('market.payment.breakdown.title') }}
        </p>
        <dl class="flex flex-col gap-1 text-sm">
          <div class="flex justify-between">
            <dt class="text-surface-600">
              {{ t('market.payment.breakdown.faceAmountLabel') }}
            </dt>
            <dd>{{ yen(view.faceAmount) }}</dd>
          </div>
          <div class="flex justify-between">
            <dt class="text-surface-600">
              {{ t('market.payment.breakdown.feeLabel') }}
            </dt>
            <dd>{{ yen(feeAmount) }}</dd>
          </div>
          <div class="mt-1 flex justify-between border-t border-surface-200 pt-1 font-semibold">
            <dt>{{ t('market.payment.breakdown.totalLabel') }}</dt>
            <dd>{{ yen(view.chargeAmount) }}</dd>
          </div>
        </dl>
        <p class="mt-2 text-xs text-surface-500">
          {{ t('market.payment.breakdown.includesFeeNote') }}
        </p>
        <p class="text-xs text-surface-500">
          {{ t('market.payment.breakdown.refundFeeConditionalNote') }}
        </p>
      </div>

      <!-- 7日失効表示（PENDING_CONFIRMATION 時・マスター裁可） -->
      <p
        v-if="needsConfirm"
        class="rounded bg-amber-50 p-2 text-xs text-amber-800"
        data-testid="escrow-expiry-notice"
      >
        {{ t('market.payment.confirm.expiryNotice') }}
      </p>

      <!-- PENDING_CONFIRMATION: PaymentElement で confirm -->
      <template v-if="needsConfirm">
        <div :id="elementDomId" />
        <p v-if="errorMessage" class="text-sm text-red-600" role="alert">
          {{ errorMessage }}
        </p>
      </template>

      <!-- DEFERRED: 7日超 fallback -->
      <p v-else-if="isDeferred" class="text-sm text-surface-700">
        {{ t('market.payment.confirm.deferredNotice') }}
      </p>

      <!-- HELD: 受取側 onboarding 未完了 -->
      <p v-else-if="isHeld" class="text-sm text-orange-700">
        {{ t('market.payment.confirm.payeeNotReady') }}
      </p>

      <!-- AUTHORIZED 以降: 確認済み -->
      <p v-else-if="alreadyAuthorized" class="text-sm text-green-700">
        {{ t('market.payment.confirm.alreadyAuthorized') }}
      </p>
    </div>

    <p v-else-if="errorMessage" class="text-sm text-red-600" role="alert">
      {{ errorMessage }}
    </p>

    <template #footer>
      <Button
        :label="t('common.cancel')"
        severity="secondary"
        text
        :disabled="submitting"
        @click="close"
      />
      <!-- PENDING_CONFIRMATION: 与信確認 -->
      <Button
        v-if="needsConfirm"
        :label="submitting
          ? t('market.payment.confirm.processing')
          : t('market.payment.confirm.submit')"
        :loading="submitting"
        :disabled="!elementMounted"
        @click="onConfirmPayment"
      />
      <!-- DEFERRED / AUTHORIZED 等: 確認不要で続行 -->
      <Button
        v-else-if="view && !isHeld && !notReady"
        :label="t('market.payment.confirm.proceed')"
        @click="onProceedWithoutConfirm"
      />
    </template>
  </Dialog>
</template>
