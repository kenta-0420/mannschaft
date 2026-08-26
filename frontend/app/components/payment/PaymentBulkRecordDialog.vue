<script setup lang="ts">
import type { MemberPaymentResponse } from '~/types/payment'
import type { MemberResponse } from '~/types/member'

/**
 * F08.9 手動入金 一括記録ダイアログ（AC-20）。
 *
 * 未払い（status==='UNPAID'）メンバーを複数選択して一括起票する。
 * 決済手段の選択肢は CASH / BANK_TRANSFER / MANUAL の3択のみ（STRIPE は出さない）。
 */

type ManualPaymentMethod = 'CASH' | 'BANK_TRANSFER' | 'MANUAL'

const props = defineProps<{
  visible: boolean
  /** 既定金額（選択中の支払い項目の金額）。 */
  defaultAmount: number
  /** 支払い一覧（このうち UNPAID のみを一括記録対象にする）。 */
  payments: MemberPaymentResponse[]
  /**
   * スコープメンバー一覧（PaymentAdminPanel から渡される）。
   * 一括記録では既存の UNPAID 行を対象にするため、このプロパティは現時点では使用しない。
   * 将来の拡張（全メンバーへの一括記録）に備えて受け取るのみ。
   */
  scopeMembers?: MemberResponse[]
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  /** 一括記録実行。各要素は単一記録と同じ camelCase body 形。 */
  (e: 'submit', bodies: Array<Record<string, unknown>>): void
}>()

const { t } = useI18n()
const { buildOffsetDateTimeStr } = useDatetime()

/**
 * 未払い（UNPAID または PENDING）メンバーのみを一括記録の候補にする。
 * BE は Stripe 未決済を PENDING で保持し、手動記録対象の「未払い」は
 * PENDING または UNPAID の両方が該当する（FE 表示は UNPAID に統一）。
 */
const unpaidMembers = computed(() =>
  props.payments.filter(
    (p) => p.statusInfo.status === 'UNPAID' || p.statusInfo.status === 'PENDING',
  ),
)

const selectedUserIds = ref<number[]>([])
const paidAt = ref<Date>(new Date())
const paymentMethod = ref<ManualPaymentMethod>('CASH')

const methodOptions = computed<Array<{ label: string; value: ManualPaymentMethod }>>(() => [
  { label: t('payment.admin.method.CASH'), value: 'CASH' },
  { label: t('payment.admin.method.BANK_TRANSFER'), value: 'BANK_TRANSFER' },
  { label: t('payment.admin.method.MANUAL'), value: 'MANUAL' },
])

watch(
  () => props.visible,
  (open) => {
    if (open) {
      selectedUserIds.value = []
      paidAt.value = new Date()
      paymentMethod.value = 'CASH'
    }
  },
)

const canSubmit = computed(() => selectedUserIds.value.length > 0)

function toggle(userId: number) {
  const i = selectedUserIds.value.indexOf(userId)
  if (i >= 0) selectedUserIds.value.splice(i, 1)
  else selectedUserIds.value.push(userId)
}

function isSelected(userId: number): boolean {
  return selectedUserIds.value.includes(userId)
}

function close() {
  emit('update:visible', false)
}

function onSubmit() {
  if (!canSubmit.value) return
  // Issue #2508: BE の paidAt は LocalDateTime で受信時オフセットを無視するため、
  // ユーザーTZのオフセット付き ISO 文字列を明示的に送る（useDatetime の共通道具を使用）。
  // paidAt は「日付のみ」ピッカー（show-time なし）のため、time に '' を明示して
  // 00:00:00 として解釈させる（省略すると Date 自身の時刻＝実行時の現在時刻が入ってしまう）。
  const at = buildOffsetDateTimeStr(paidAt.value, '')
  // 解決できない場合は不正な状態として送信をブロックする（旧形式へのフォールバックはしない）。
  if (!at) return
  const bodies = selectedUserIds.value.map<Record<string, unknown>>((userId) => ({
    userId,
    amountPaid: props.defaultAmount,
    paidAt: at,
    paymentMethod: paymentMethod.value,
  }))
  emit('submit', bodies)
}

defineExpose({ selectedUserIds, paymentMethod, methodOptions, canSubmit, unpaidMembers, toggle, onSubmit })
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    style="width: 520px"
    :header="t('payment.admin.bulk.title')"
    data-testid="payment-bulk-dialog"
    @update:visible="emit('update:visible', $event)"
  >
    <div class="flex flex-col gap-4">
      <p class="text-sm text-surface-500">{{ t('payment.admin.bulk.description') }}</p>

      <div v-if="unpaidMembers.length === 0" class="rounded-lg border border-dashed border-surface-300 py-6 text-center text-sm text-surface-400">
        {{ t('payment.admin.bulk.noUnpaid') }}
      </div>
      <div v-else class="flex max-h-64 flex-col gap-1 overflow-y-auto" data-testid="payment-bulk-member-list">
        <label
          v-for="m in unpaidMembers"
          :key="m.userId"
          class="flex items-center gap-2 rounded-lg border border-surface-100 px-3 py-2"
          :data-testid="`payment-bulk-member-${m.userId}`"
        >
          <Checkbox
            :model-value="isSelected(m.userId)"
            :binary="true"
            @update:model-value="toggle(m.userId)"
          />
          <span class="text-sm">{{ m.userName }}</span>
        </label>
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ t('payment.admin.record.paidAt') }}</label>
        <DatePicker v-model="paidAt" date-format="yy-mm-dd" show-icon class="w-full" />
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ t('payment.admin.record.method') }}</label>
        <Select
          v-model="paymentMethod"
          :options="methodOptions"
          option-label="label"
          option-value="value"
          class="w-full"
          data-testid="payment-bulk-method"
        />
      </div>
    </div>

    <template #footer>
      <Button
        :label="t('payment.admin.record.cancel')"
        text
        data-testid="payment-bulk-cancel"
        @click="close"
      />
      <Button
        :label="t('payment.admin.bulk.submit', { count: selectedUserIds.length })"
        :disabled="!canSubmit"
        data-testid="payment-bulk-submit"
        @click="onSubmit"
      />
    </template>
  </Dialog>
</template>
