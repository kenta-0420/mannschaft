<script setup lang="ts">
/**
 * F18 Phase 3 第三陣 3B — 残高チャージ操作タブパネル。
 *
 * <p>設計書: docs/features/F18_point_card_wallet.md §12.1 / §6
 *
 * <p>SELF_ISSUED_BALANCE カードに対する CHARGE 操作のフォームを提供する。
 * 親（stamp.vue）から {@code cardId} を受け、金額・メモを入力させて
 * {@code recordBalanceEvent} を呼ぶ。成功時に {@code done} を emit して
 * カード状態の再取得を親に委譲する。
 *
 * <p>バリデーション:
 * <ul>
 *   <li>金額は 0.01 〜 1,000,000.00（バックエンド DecimalMin/Max と同等）</li>
 *   <li>0 / 負数は不可</li>
 * </ul>
 *
 * <p>主なエラーコード:
 * <ul>
 *   <li>POINT_CARD_018 — BALANCE_LIMIT_EXCEEDED（残高上限超過）</li>
 * </ul>
 */
import type { FetchError } from 'ofetch'
import type { BalanceEventResponse } from '~/types/orgPointCard'
import { useToast } from 'primevue/usetoast'

interface Props {
  cardId: string
  orgId: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  done: [event: BalanceEventResponse]
}>()

const { t } = useI18n()
const toast = useToast()
const api = useOrgWalletApi(() => props.orgId)

const amount = ref<number>(0)
const note = ref('')
const submitting = ref(false)
const submitError = ref<string | null>(null)

const AMOUNT_MIN = 0.01
const AMOUNT_MAX = 1_000_000

const amountError = computed(() => {
  if (amount.value <= 0) return null // 未入力時はエラー表示しない
  if (amount.value < AMOUNT_MIN) return t('wallet.admin.balance.amount_min')
  if (amount.value > AMOUNT_MAX) return t('wallet.admin.balance.amount_max')
  return null
})

const canSubmit = computed(() =>
  !submitting.value
  && amount.value >= AMOUNT_MIN
  && amount.value <= AMOUNT_MAX,
)

function errorCodeMessage(code: string | undefined): string | null {
  switch (code) {
    case 'POINT_CARD_006': return t('wallet.admin.stamp.error_card_not_found')
    case 'POINT_CARD_011': return t('wallet.admin.stamp.error_not_owned')
    case 'POINT_CARD_012': return t('wallet.admin.stamp.error_invalid_provider')
    case 'POINT_CARD_015': return t('wallet.admin.balance.error_invalid_provider_type')
    case 'POINT_CARD_016': return t('wallet.admin.balance.error_amount_zero')
    case 'POINT_CARD_018': return t('wallet.admin.balance.error_balance_limit_exceeded')
    case 'POINT_CARD_008': return t('wallet.admin.stamp.error_rate_limit')
    default: return null
  }
}

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  submitError.value = null
  try {
    const event = await api.recordBalanceEvent(props.cardId, {
      operationType: 'CHARGE',
      amount: amount.value,
      note: note.value.trim() || undefined,
    })
    toast.add({
      severity: 'success',
      summary: t('wallet.admin.balance.success_charge', { amount: amount.value }),
      life: 3000,
    })
    amount.value = 0
    note.value = ''
    emit('done', event)
  } catch (e) {
    const fe = e as FetchError<{ errorCode?: string; message?: string }>
    const code = fe.data?.errorCode
    const msg = errorCodeMessage(code) ?? fe.data?.message ?? t('wallet.admin.errors.save_failed')
    submitError.value = msg
    toast.add({ severity: 'error', summary: msg, life: 5000 })
    console.error('[BalanceChargeTabPanel] submit failed', e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="space-y-4">
    <div>
      <label for="charge-amount" class="mb-1 block text-sm font-medium">
        {{ t('wallet.admin.balance.amount_label') }}
        <span class="text-red-500">*</span>
      </label>
      <input
        id="charge-amount"
        v-model.number="amount"
        type="number"
        :min="AMOUNT_MIN"
        :max="AMOUNT_MAX"
        step="0.01"
        class="w-full rounded border border-surface-300 px-3 py-2 text-right font-mono dark:border-surface-600 dark:bg-surface-800"
        :placeholder="t('wallet.admin.balance.amount_placeholder')"
        :aria-invalid="!!amountError"
      >
      <p v-if="amountError" class="mt-1 text-xs text-red-600" role="alert">
        {{ amountError }}
      </p>
    </div>

    <div>
      <label for="charge-note" class="mb-1 block text-sm font-medium">
        {{ t('wallet.admin.balance.note_label') }}
      </label>
      <input
        id="charge-note"
        v-model="note"
        type="text"
        maxlength="200"
        class="w-full rounded border border-surface-300 px-3 py-2 dark:border-surface-600 dark:bg-surface-800"
      >
    </div>

    <div
      v-if="submitError"
      class="rounded border border-red-300 bg-red-50 p-3 text-sm text-red-700 dark:border-red-700 dark:bg-red-950 dark:text-red-300"
      role="alert"
    >
      {{ submitError }}
    </div>

    <button
      type="button"
      class="w-full rounded bg-primary-600 px-4 py-3 text-base font-semibold text-white hover:bg-primary-700 disabled:opacity-50"
      :disabled="!canSubmit"
      @click="submit"
    >
      {{ submitting ? t('wallet.admin.actions.processing') : t('wallet.admin.balance.charge_button') }}
    </button>
  </div>
</template>
