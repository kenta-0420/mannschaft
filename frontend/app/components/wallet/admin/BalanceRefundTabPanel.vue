<script setup lang="ts">
/**
 * F18 Phase 3 第三陣 3B — 残高返金（REFUND）操作タブパネル。
 *
 * <p>設計書: docs/features/F18_point_card_wallet.md §12.1 / §6
 *
 * <p>SELF_ISSUED_BALANCE カードに対する REFUND 操作のフォームを提供する。
 * このカードの SPENT 履歴をドロップダウンで選ばせ、その元 event ID を
 * {@code refundOfEventId} として送信する。累計返金額超過は POINT_CARD_020 で
 * Backend が根治するが、UI 側でも元 event の |delta| 以下にバリデーションする。
 *
 * <p>元 event の選択肢は SPENT のみ（CHARGE/REFUND は除外）。
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
const { formatDateTime: formatDateTimeTz } = useDatetime()
const toast = useToast()
const api = useOrgWalletApi(() => props.orgId)

const events = ref<BalanceEventResponse[]>([])
const loading = ref(false)
const selectedEventId = ref<string>('')
const amount = ref<number>(0)
const note = ref('')
const submitting = ref(false)
const submitError = ref<string | null>(null)

const spentEvents = computed(() =>
  events.value.filter(e => e.operationType === 'SPENT'),
)

const selectedEvent = computed(() =>
  spentEvents.value.find(e => e.id === selectedEventId.value) ?? null,
)

/**
 * 元 SPENT イベントの返金可能上限（|delta| を絶対値で）。
 * 既存の REFUND との累計超過チェックは Backend POINT_CARD_020 が最終防衛線。
 */
const maxRefundAmount = computed(() => {
  if (!selectedEvent.value) return 0
  return Math.abs(parseFloat(selectedEvent.value.delta))
})

const amountError = computed(() => {
  if (amount.value <= 0) return null
  if (amount.value < 0.01) return t('wallet.admin.balance.amount_min')
  if (selectedEvent.value && amount.value > maxRefundAmount.value) {
    return t('wallet.admin.balance.refund_exceeds_original')
  }
  return null
})

const canSubmit = computed(() =>
  !submitting.value
  && !!selectedEventId.value
  && amount.value >= 0.01
  && (!selectedEvent.value || amount.value <= maxRefundAmount.value),
)

async function fetchEvents() {
  loading.value = true
  try {
    events.value = await api.listCardBalanceEvents(props.cardId)
  } catch (e) {
    console.error('[BalanceRefundTabPanel] listCardBalanceEvents failed', e)
    events.value = []
  } finally {
    loading.value = false
  }
}

watch(() => props.cardId, () => {
  selectedEventId.value = ''
  amount.value = 0
  note.value = ''
  void fetchEvents()
}, { immediate: true })

// 元 event 変更時に金額を上限にプリセット（運用補助）
watch(selectedEventId, () => {
  if (selectedEvent.value) {
    amount.value = maxRefundAmount.value
  } else {
    amount.value = 0
  }
})

function formatDateTime(iso: string): string {
  return formatDateTimeTz(iso) || iso
}

function errorCodeMessage(code: string | undefined): string | null {
  switch (code) {
    case 'POINT_CARD_006': return t('wallet.admin.stamp.error_card_not_found')
    case 'POINT_CARD_011': return t('wallet.admin.stamp.error_not_owned')
    case 'POINT_CARD_012': return t('wallet.admin.stamp.error_invalid_provider')
    case 'POINT_CARD_015': return t('wallet.admin.balance.error_invalid_provider_type')
    case 'POINT_CARD_016': return t('wallet.admin.balance.error_amount_zero')
    case 'POINT_CARD_020': return t('wallet.admin.balance.refund_exceeds_original')
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
      operationType: 'REFUND',
      amount: amount.value,
      note: note.value.trim() || undefined,
      refundOfEventId: selectedEventId.value,
    })
    toast.add({
      severity: 'success',
      summary: t('wallet.admin.balance.success_refund', { amount: amount.value }),
      life: 3000,
    })
    selectedEventId.value = ''
    amount.value = 0
    note.value = ''
    // 残高履歴も最新化（次の返金の判定に影響するため）
    await fetchEvents()
    emit('done', event)
  } catch (e) {
    const fe = e as FetchError<{ errorCode?: string; message?: string }>
    const code = fe.data?.errorCode
    const msg = errorCodeMessage(code) ?? fe.data?.message ?? t('wallet.admin.errors.save_failed')
    submitError.value = msg
    toast.add({ severity: 'error', summary: msg, life: 5000 })
    console.error('[BalanceRefundTabPanel] submit failed', e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="space-y-4">
    <div>
      <label for="refund-event" class="mb-1 block text-sm font-medium">
        {{ t('wallet.admin.balance.refund_select_event') }}
        <span class="text-red-500">*</span>
      </label>
      <select
        id="refund-event"
        v-model="selectedEventId"
        class="w-full rounded border border-surface-300 px-3 py-2 dark:border-surface-600 dark:bg-surface-800"
        :disabled="loading || spentEvents.length === 0"
      >
        <option v-if="loading" value="">
          {{ t('wallet.admin.providers.loading') }}
        </option>
        <option v-else-if="spentEvents.length === 0" value="">
          {{ t('wallet.admin.balance.refund_no_events') }}
        </option>
        <template v-else>
          <option value="">
            ─────
          </option>
          <option v-for="e in spentEvents" :key="e.id" :value="e.id">
            {{ formatDateTime(e.operatedAt) }} — ¥{{ Math.abs(parseFloat(e.delta)).toLocaleString() }}
            {{ e.note ? `（${e.note}）` : '' }}
          </option>
        </template>
      </select>
    </div>

    <div v-if="selectedEvent">
      <label for="refund-amount" class="mb-1 block text-sm font-medium">
        {{ t('wallet.admin.balance.amount_label') }}
        <span class="text-red-500">*</span>
      </label>
      <input
        id="refund-amount"
        v-model.number="amount"
        type="number"
        :min="0.01"
        :max="maxRefundAmount"
        step="0.01"
        class="w-full rounded border border-surface-300 px-3 py-2 text-right font-mono dark:border-surface-600 dark:bg-surface-800"
        :placeholder="t('wallet.admin.balance.amount_placeholder')"
        :aria-invalid="!!amountError"
      >
      <p class="mt-1 text-xs text-surface-500">
        {{ t('wallet.admin.balance.refund_max_hint', { max: maxRefundAmount.toLocaleString() }) }}
      </p>
      <p v-if="amountError" class="mt-1 text-xs text-red-600" role="alert">
        {{ amountError }}
      </p>
    </div>

    <div v-if="selectedEvent">
      <label for="refund-note" class="mb-1 block text-sm font-medium">
        {{ t('wallet.admin.balance.note_label') }}
      </label>
      <input
        id="refund-note"
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
      {{ submitting ? t('wallet.admin.actions.processing') : t('wallet.admin.balance.refund_button') }}
    </button>
  </div>
</template>
