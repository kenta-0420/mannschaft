<script setup lang="ts">
/**
 * F18 Phase 3 第三陣 3B — 残高利用（SPENT）操作タブパネル。
 *
 * <p>設計書: docs/features/F18_point_card_wallet.md §12.1 / §6
 *
 * <p>SELF_ISSUED_BALANCE カードに対する SPENT 操作のフォームを提供する。
 * クライアント側で現在残高との照合バリデーションを行い、Backend では
 * POINT_CARD_017 (INSUFFICIENT_BALANCE) で根治される（多層防御）。
 *
 * <p>{@code amount} は常に正の値で送信し、Service 層で負に変換される。
 */
import type { FetchError } from 'ofetch'
import type { BalanceEventResponse } from '~/types/orgPointCard'
import { useToast } from 'primevue/usetoast'

interface Props {
  cardId: string
  orgId: string
  /** 現在残高（resolve 直後の値。送信完了で done emit して親が再取得する） */
  currentBalance: number
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
  if (amount.value <= 0) return null
  if (amount.value < AMOUNT_MIN) return t('wallet.admin.balance.amount_min')
  if (amount.value > AMOUNT_MAX) return t('wallet.admin.balance.amount_max')
  if (amount.value > props.currentBalance) return t('wallet.admin.balance.insufficient_balance')
  return null
})

const canSubmit = computed(() =>
  !submitting.value
  && amount.value >= AMOUNT_MIN
  && amount.value <= AMOUNT_MAX
  && amount.value <= props.currentBalance,
)

function errorCodeMessage(code: string | undefined): string | null {
  switch (code) {
    case 'POINT_CARD_006': return t('wallet.admin.stamp.error_card_not_found')
    case 'POINT_CARD_011': return t('wallet.admin.stamp.error_not_owned')
    case 'POINT_CARD_012': return t('wallet.admin.stamp.error_invalid_provider')
    case 'POINT_CARD_015': return t('wallet.admin.balance.error_invalid_provider_type')
    case 'POINT_CARD_016': return t('wallet.admin.balance.error_amount_zero')
    case 'POINT_CARD_017': return t('wallet.admin.balance.insufficient_balance')
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
      operationType: 'SPENT',
      amount: amount.value,
      note: note.value.trim() || undefined,
    })
    toast.add({
      severity: 'success',
      summary: t('wallet.admin.balance.success_spent', { amount: amount.value }),
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
    console.error('[BalanceSpendTabPanel] submit failed', e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="space-y-4">
    <div class="rounded bg-surface-50 p-3 text-sm dark:bg-surface-800">
      <span class="text-surface-600 dark:text-surface-400">
        {{ t('wallet.admin.balance.current_balance') }}:
      </span>
      <span class="ml-1 font-mono font-semibold">
        ¥{{ currentBalance.toLocaleString() }}
      </span>
    </div>

    <div>
      <label for="spend-amount" class="mb-1 block text-sm font-medium">
        {{ t('wallet.admin.balance.amount_label') }}
        <span class="text-red-500">*</span>
      </label>
      <input
        id="spend-amount"
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
      <label for="spend-note" class="mb-1 block text-sm font-medium">
        {{ t('wallet.admin.balance.note_label') }}
      </label>
      <input
        id="spend-note"
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
      {{ submitting ? t('wallet.admin.actions.processing') : t('wallet.admin.balance.spent_button') }}
    </button>
  </div>
</template>
