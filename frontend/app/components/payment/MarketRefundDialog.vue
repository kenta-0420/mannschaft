<script setup lang="ts">
/**
 * F22.1 市の謝礼決済: エスクロー返金ダイアログ（受取側 ADMIN）。
 *
 * BE 配線（recon 済み・実在 EP）:
 *   - POST /api/v1/payment/escrow/{id}/refund
 *     body = { amount?, feeBearer?(PAYER|PAYEE), reason?, reasonDetail? }（RefundRequest.java）
 *
 * 機能:
 *   - feeBearer 選択（PAYER=支払者負担 / PAYEE=受取側負担）。各モードの説明 hint を表示。
 *   - 金額（全額 / 一部）。一部時のみ金額入力。
 *   - 理由（reason）・補足（reasonDetail）。
 *   - PAYEE モードは追加の確認ダイアログ（手数料が受取側負担になる旨）を挟む。
 *
 * i18n は既存 market.payment.refund.* を再利用する。
 */
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import InputNumber from 'primevue/inputnumber'
import Textarea from 'primevue/textarea'
import RadioButton from 'primevue/radiobutton'
import type { FeeBearer, MarketRefundResponse } from '~/types/marketPayment'

interface Props {
  /** ダイアログ表示状態（v-model:visible）。 */
  visible: boolean
  /** 返金対象のエスクロー取引 ID。 */
  escrowId: string
  /** 返金可能な上限額（額面ベース・任意。指定時は一部金額の上限に使う）。 */
  maxAmount?: number | null
}

const props = defineProps<Props>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  refunded: [result: MarketRefundResponse]
}>()

const { t } = useI18n()
const marketPaymentApi = useMarketPaymentApi()

const feeBearer = ref<FeeBearer>('PAYER')
/** 'full'=全額 / 'partial'=一部。 */
const amountMode = ref<'full' | 'partial'>('full')
const partialAmount = ref<number | null>(null)
const reason = ref<string>('')
const reasonDetail = ref<string>('')

const submitting = ref(false)
const errorMessage = ref<string | null>(null)
/** PAYEE モードの最終確認ダイアログ表示状態。 */
const showPayeeConfirm = ref(false)

/** 現在の feeBearer に対応する hint 文言。 */
const feeBearerHint = computed(() =>
  feeBearer.value === 'PAYER'
    ? t('market.payment.refund.feeBearerHint.PAYER')
    : t('market.payment.refund.feeBearerHint.PAYEE'),
)

function reset() {
  feeBearer.value = 'PAYER'
  amountMode.value = 'full'
  partialAmount.value = null
  reason.value = ''
  reasonDetail.value = ''
  errorMessage.value = null
  showPayeeConfirm.value = false
}

function close() {
  emit('update:visible', false)
}

/** 送信前バリデーション。一部金額モードでは正の金額が必須。 */
function validate(): boolean {
  errorMessage.value = null
  if (amountMode.value === 'partial') {
    if (!partialAmount.value || partialAmount.value <= 0) {
      errorMessage.value = t('market.payment.refund.amountRequired')
      return false
    }
    if (props.maxAmount != null && partialAmount.value > props.maxAmount) {
      errorMessage.value = t('market.payment.refund.amountTooLarge')
      return false
    }
  }
  return true
}

function onSubmitClick() {
  if (!validate()) {
    return
  }
  // PAYEE は手数料が受取側負担になるため最終確認を挟む。
  if (feeBearer.value === 'PAYEE') {
    showPayeeConfirm.value = true
    return
  }
  void doRefund()
}

async function doRefund() {
  if (submitting.value) {
    return
  }
  submitting.value = true
  errorMessage.value = null
  showPayeeConfirm.value = false
  try {
    const res = await marketPaymentApi.refund(props.escrowId, {
      amount: amountMode.value === 'partial' ? partialAmount.value : null,
      feeBearer: feeBearer.value,
      reason: reason.value.trim() || null,
      reasonDetail: reasonDetail.value.trim() || null,
    })
    emit('refunded', res.data)
    reset()
    close()
  } catch (e: unknown) {
    errorMessage.value
      = e instanceof Error ? e.message : t('market.payment.refund.failed')
  } finally {
    submitting.value = false
  }
}

// ダイアログを開くたびに初期化する。
watch(
  () => props.visible,
  (v) => {
    if (v) {
      reset()
    }
  },
)
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    :draggable="false"
    :header="t('market.payment.refund.title')"
    :style="{ width: '32rem' }"
    :breakpoints="{ '640px': '92vw' }"
    @update:visible="(v: boolean) => emit('update:visible', v)"
  >
    <div class="flex flex-col gap-4">
      <!-- feeBearer 選択 -->
      <div>
        <p class="block text-sm font-medium mb-2">
          {{ t('market.payment.refund.feeBearerLabel') }}
        </p>
        <div class="flex flex-col gap-2">
          <label class="flex items-center gap-2 cursor-pointer">
            <RadioButton v-model="feeBearer" input-id="feeBearer-payer" value="PAYER" />
            <span>{{ t('market.payment.refund.feeBearer.PAYER') }}</span>
          </label>
          <label class="flex items-center gap-2 cursor-pointer">
            <RadioButton v-model="feeBearer" input-id="feeBearer-payee" value="PAYEE" />
            <span>{{ t('market.payment.refund.feeBearer.PAYEE') }}</span>
          </label>
        </div>
        <p class="text-xs text-surface-500 mt-1">
          {{ feeBearerHint }}
        </p>
      </div>

      <!-- 金額: 全額 / 一部 -->
      <div>
        <p class="block text-sm font-medium mb-2">
          {{ t('market.payment.refund.amountLabel') }}
        </p>
        <div class="flex flex-col gap-2">
          <label class="flex items-center gap-2 cursor-pointer">
            <RadioButton v-model="amountMode" input-id="amount-full" value="full" />
            <span>{{ t('market.payment.refund.amountFull') }}</span>
          </label>
          <label class="flex items-center gap-2 cursor-pointer">
            <RadioButton v-model="amountMode" input-id="amount-partial" value="partial" />
            <span>{{ t('market.payment.refund.amountPartial') }}</span>
          </label>
        </div>
        <div v-if="amountMode === 'partial'" class="mt-2">
          <InputNumber
            v-model="partialAmount"
            class="w-full"
            mode="currency"
            currency="JPY"
            :min="1"
            :max="maxAmount ?? undefined"
          />
        </div>
        <p class="text-xs text-surface-500 mt-1">
          {{ t('market.payment.breakdown.refundFeeConditionalNote') }}
        </p>
      </div>

      <!-- 理由 -->
      <div>
        <label class="block text-sm font-medium mb-1">
          {{ t('market.payment.refund.reasonLabel') }}
        </label>
        <Textarea v-model="reasonDetail" class="w-full" rows="2" :maxlength="500" />
      </div>

      <p v-if="errorMessage" class="text-red-600 text-sm" role="alert">
        {{ errorMessage }}
      </p>
    </div>

    <template #footer>
      <Button
        :label="t('common.cancel')"
        severity="secondary"
        text
        :disabled="submitting"
        @click="close"
      />
      <Button
        :label="t('market.payment.refund.submit')"
        :loading="submitting"
        @click="onSubmitClick"
      />
    </template>
  </Dialog>

  <!-- PAYEE モード最終確認 -->
  <Dialog
    :visible="showPayeeConfirm"
    modal
    :draggable="false"
    :header="t('market.payment.refund.confirmTitle')"
    :style="{ width: '28rem' }"
    :breakpoints="{ '640px': '92vw' }"
    @update:visible="(v: boolean) => (showPayeeConfirm = v)"
  >
    <p class="text-sm">
      {{ t('market.payment.refund.confirmPayee') }}
    </p>
    <template #footer>
      <Button
        :label="t('common.cancel')"
        severity="secondary"
        text
        :disabled="submitting"
        @click="showPayeeConfirm = false"
      />
      <Button
        :label="t('market.payment.refund.submit')"
        :loading="submitting"
        @click="doRefund"
      />
    </template>
  </Dialog>
</template>
