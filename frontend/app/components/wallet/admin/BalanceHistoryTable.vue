<script setup lang="ts">
/**
 * F18 Phase 3 第三陣 3B — 残高変動履歴テーブル。
 *
 * <p>設計書: docs/features/F18_point_card_wallet.md §12.1
 *
 * <p>列: operatedAt / providerDisplayName / operatedByUserDisplayName /
 * operationType / delta / balanceAfter / note / cardId
 *
 * <p>cardId はコピーボタン付き。証拠ログとして組織内全カードの残高変動を一覧する。
 */
import type { BalanceEventResponse } from '~/types/orgPointCard'

interface Props {
  events: BalanceEventResponse[]
  loading?: boolean
}

withDefaults(defineProps<Props>(), { loading: false })

const { t, locale } = useI18n()

const copiedCardId = ref<string | null>(null)

function formatDateTime(iso: string): string {
  try {
    return new Date(iso).toLocaleString(locale.value, {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return iso
  }
}

function formatAmount(value: string): string {
  const num = parseFloat(value)
  if (Number.isNaN(num)) return value
  return num.toLocaleString(undefined, { minimumFractionDigits: 0, maximumFractionDigits: 2 })
}

function operationLabel(op: BalanceEventResponse['operationType']): string {
  switch (op) {
    case 'CHARGE': return t('wallet.admin.history.op_charge')
    case 'SPENT': return t('wallet.admin.history.op_spent')
    case 'REFUND': return t('wallet.admin.history.op_refund')
    default: return op
  }
}

async function copyCardId(cardId: string) {
  try {
    await navigator.clipboard.writeText(cardId)
    copiedCardId.value = cardId
    setTimeout(() => {
      if (copiedCardId.value === cardId) copiedCardId.value = null
    }, 1500)
  } catch (e) {
    console.warn('[BalanceHistoryTable] clipboard write failed', e)
  }
}
</script>

<template>
  <div class="overflow-x-auto">
    <table class="w-full min-w-[720px] border-collapse text-sm">
      <thead class="bg-surface-100 text-left dark:bg-surface-800">
        <tr>
          <th class="border-b border-surface-300 px-3 py-2 dark:border-surface-700">
            {{ t('wallet.admin.history.col_operated_at') }}
          </th>
          <th class="border-b border-surface-300 px-3 py-2 dark:border-surface-700">
            {{ t('wallet.admin.history.col_provider') }}
          </th>
          <th class="border-b border-surface-300 px-3 py-2 dark:border-surface-700">
            {{ t('wallet.admin.history.col_staff') }}
          </th>
          <th class="border-b border-surface-300 px-3 py-2 dark:border-surface-700">
            {{ t('wallet.admin.history.col_operation') }}
          </th>
          <th class="border-b border-surface-300 px-3 py-2 text-right dark:border-surface-700">
            {{ t('wallet.admin.history.col_amount') }}
          </th>
          <th class="border-b border-surface-300 px-3 py-2 text-right dark:border-surface-700">
            {{ t('wallet.admin.history.col_balance_after') }}
          </th>
          <th class="border-b border-surface-300 px-3 py-2 dark:border-surface-700">
            {{ t('wallet.admin.history.col_memo') }}
          </th>
          <th class="border-b border-surface-300 px-3 py-2 dark:border-surface-700">
            {{ t('wallet.admin.history.col_card_id') }}
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="loading">
          <td colspan="8" class="px-3 py-6 text-center text-surface-500">
            {{ t('wallet.admin.history.loading') }}
          </td>
        </tr>
        <tr v-else-if="events.length === 0">
          <td colspan="8" class="px-3 py-6 text-center text-surface-500">
            {{ t('wallet.admin.history.balance_empty') }}
          </td>
        </tr>
        <tr
          v-for="ev in events"
          v-else
          :key="ev.id"
          class="border-b border-surface-200 hover:bg-surface-50 dark:border-surface-800 dark:hover:bg-surface-800"
        >
          <td class="px-3 py-2 whitespace-nowrap font-mono text-xs">
            {{ formatDateTime(ev.operatedAt) }}
          </td>
          <td class="px-3 py-2">
            {{ ev.providerDisplayName ?? '—' }}
          </td>
          <td class="px-3 py-2">
            {{ ev.operatedByUserDisplayName ?? '—' }}
          </td>
          <td class="px-3 py-2">
            <span
              class="inline-flex rounded px-2 py-0.5 text-xs font-medium"
              :class="{
                'bg-emerald-100 text-emerald-800 dark:bg-emerald-900 dark:text-emerald-200': ev.operationType === 'CHARGE',
                'bg-rose-100 text-rose-800 dark:bg-rose-900 dark:text-rose-200': ev.operationType === 'SPENT',
                'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200': ev.operationType === 'REFUND',
              }"
            >
              {{ operationLabel(ev.operationType) }}
            </span>
          </td>
          <td class="px-3 py-2 text-right font-mono">
            <span :class="{ 'text-emerald-700 dark:text-emerald-300': ev.operationType !== 'SPENT', 'text-rose-700 dark:text-rose-300': ev.operationType === 'SPENT' }">
              {{ ev.operationType === 'SPENT' ? '' : '+' }}{{ formatAmount(ev.delta) }}
            </span>
          </td>
          <td class="px-3 py-2 text-right font-mono">
            {{ formatAmount(ev.balanceAfter) }}
          </td>
          <td class="px-3 py-2 text-surface-700 dark:text-surface-300">
            {{ ev.note ?? '' }}
          </td>
          <td class="px-3 py-2">
            <button
              type="button"
              class="inline-flex items-center gap-1 rounded border border-surface-300 px-2 py-1 font-mono text-xs hover:bg-surface-100 dark:border-surface-600 dark:hover:bg-surface-800"
              :aria-label="t('wallet.admin.history.copy_card_id')"
              @click="copyCardId(ev.cardId)"
            >
              <span>{{ ev.cardId.slice(0, 8) }}…</span>
              <span v-if="copiedCardId === ev.cardId" class="text-emerald-600 dark:text-emerald-400">
                {{ t('wallet.admin.actions.copied') }}
              </span>
              <span v-else>{{ t('wallet.admin.actions.copy') }}</span>
            </button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
