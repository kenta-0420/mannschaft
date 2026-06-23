<script setup lang="ts">
import type { MemberPaymentResponse } from '~/types/payment'
import type { MemberResponse } from '~/types/member'

/**
 * F08.9 手動入金 記録ダイアログ（AC-16/AC-17）。
 *
 * 決済手段セレクトの選択肢は CASH / BANK_TRANSFER / MANUAL の3択のみ。
 * STRIPE は手動記録 UI には絶対に出さない（オンライン決済専用のため）。
 */

/** 手動記録で選択可能な決済手段（STRIPE を含まない）。 */
type ManualPaymentMethod = 'CASH' | 'BANK_TRANSFER' | 'MANUAL'

const props = defineProps<{
  visible: boolean
  /** 既定金額（選択中の支払い項目の金額）。 */
  defaultAmount: number
  /** メンバー選択肢の元データ（支払い一覧）。 */
  payments: MemberPaymentResponse[]
  /**
   * スコープメンバー一覧（team / organization 両スコープ）。
   * 指定されている場合は payments の代わりにこちらからメンバー選択肢を生成する。
   * 新規 payment-item では payments が空になるため、スコープメンバー全員を選択できるようにする。
   */
  scopeMembers?: MemberResponse[]
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  /** 記録実行。LocalDateTime 形式の paidAt を含む camelCase body を渡す。 */
  (e: 'submit', body: Record<string, unknown>): void
}>()

const { t } = useI18n()

/** 決済手段の選択肢（STRIPE は除外）。 */
const methodOptions = computed<Array<{ label: string; value: ManualPaymentMethod }>>(() => [
  { label: t('payment.admin.method.CASH'), value: 'CASH' },
  { label: t('payment.admin.method.BANK_TRANSFER'), value: 'BANK_TRANSFER' },
  { label: t('payment.admin.method.MANUAL'), value: 'MANUAL' },
])

/**
 * メンバー選択肢。
 * scopeMembers が指定されていればそこから生成（新規 payment-item でも全メンバー選択可）。
 * scopeMembers がない場合は payments からフォールバック。
 */
const memberOptions = computed<Array<{ label: string; value: number }>>(() => {
  if (props.scopeMembers && props.scopeMembers.length > 0) {
    return props.scopeMembers.map((m) => ({ label: m.displayName, value: m.userId }))
  }
  return props.payments.map((p) => ({ label: p.userName, value: p.userId }))
})

const userId = ref<number | null>(null)
const amountPaid = ref<number>(props.defaultAmount)
const paidAt = ref<Date>(new Date())
const note = ref<string>('')
const paymentMethod = ref<ManualPaymentMethod>('CASH')

/** ダイアログが開かれるたびにフォームを初期化する。 */
watch(
  () => props.visible,
  (open) => {
    if (open) {
      userId.value = null
      amountPaid.value = props.defaultAmount
      paidAt.value = new Date()
      note.value = ''
      paymentMethod.value = 'CASH'
    }
  },
)

const canSubmit = computed(
  () => userId.value != null && amountPaid.value != null && amountPaid.value >= 0.01,
)

/**
 * Date を LocalDateTime 形式 `YYYY-MM-DDT00:00:00` に変換する。
 * タイムゾーンオフセットは付けない（BE は LocalDateTime を期待）。
 */
function toLocalDateTime(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}T00:00:00`
}

function close() {
  emit('update:visible', false)
}

function onSubmit() {
  if (!canSubmit.value || userId.value == null) return
  const body: Record<string, unknown> = {
    userId: userId.value,
    amountPaid: amountPaid.value,
    paidAt: toLocalDateTime(paidAt.value),
    paymentMethod: paymentMethod.value,
  }
  if (note.value.trim().length > 0) body.note = note.value.trim()
  emit('submit', body)
}

defineExpose({ userId, amountPaid, paidAt, note, paymentMethod, canSubmit, methodOptions, onSubmit })
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    style="width: 480px"
    :header="t('payment.admin.record.title')"
    data-testid="payment-record-dialog"
    @update:visible="emit('update:visible', $event)"
  >
    <div class="flex flex-col gap-4">
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ t('payment.admin.record.member') }} <span class="text-red-500">*</span></label>
        <Select
          v-model="userId"
          :options="memberOptions"
          option-label="label"
          option-value="value"
          :placeholder="t('payment.admin.record.memberPlaceholder')"
          class="w-full"
          data-testid="payment-record-member"
        />
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ t('payment.admin.record.amount') }} <span class="text-red-500">*</span></label>
        <InputNumber
          v-model="amountPaid"
          :min="0.01"
          mode="currency"
          currency="JPY"
          locale="ja-JP"
          class="w-full"
          data-testid="payment-record-amount"
        />
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ t('payment.admin.record.paidAt') }} <span class="text-red-500">*</span></label>
        <DatePicker
          v-model="paidAt"
          date-format="yy-mm-dd"
          show-icon
          class="w-full"
          data-testid="payment-record-paidat"
        />
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ t('payment.admin.record.method') }}</label>
        <Select
          v-model="paymentMethod"
          :options="methodOptions"
          option-label="label"
          option-value="value"
          class="w-full"
          data-testid="payment-record-method"
        />
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ t('payment.admin.record.note') }}</label>
        <Textarea
          v-model="note"
          rows="2"
          :maxlength="500"
          :placeholder="t('payment.admin.record.notePlaceholder')"
          class="w-full"
          data-testid="payment-record-note"
        />
      </div>
    </div>

    <template #footer>
      <Button
        :label="t('payment.admin.record.cancel')"
        text
        data-testid="payment-record-cancel"
        @click="close"
      />
      <Button
        :label="t('payment.admin.record.submit')"
        :disabled="!canSubmit"
        data-testid="payment-record-submit"
        @click="onSubmit"
      />
    </template>
  </Dialog>
</template>
