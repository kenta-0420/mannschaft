<script setup lang="ts">
/**
 * F18 Phase 2 — スタンプ押印履歴テーブル。
 *
 * <p>設計書: docs/features/F18_point_card_wallet.md §12.2「証拠ログ」
 *
 * <p>列: pressedAt / providerDisplayName / pressedByUserDisplayName / delta / memo / cardId
 * <p>cardId はコピーボタン付き（押印画面で再利用可能）。
 *
 * <p>無限スクロール対応はせず、親が page/size を渡してページングを制御する設計。
 */
import dayjs from 'dayjs'
import type { StampEventResponse } from '~/types/orgPointCard'

interface Props {
  stamps: StampEventResponse[]
  loading?: boolean
}

withDefaults(defineProps<Props>(), { loading: false })

const { t } = useI18n()
const { userTimezone } = useDatetime()

const copiedCardId = ref<string | null>(null)

function formatDateTime(iso: string): string {
  try {
    return dayjs(iso).tz(userTimezone.value).format('YYYY/MM/DD HH:mm:ss')
  } catch {
    return iso
  }
}

function shortCardId(cardId: string): string {
  return cardId.length > 8 ? `${cardId.substring(0, 8)}…` : cardId
}

async function copyCardId(cardId: string) {
  try {
    await navigator.clipboard.writeText(cardId)
    copiedCardId.value = cardId
    setTimeout(() => {
      if (copiedCardId.value === cardId) copiedCardId.value = null
    }, 2000)
  } catch (e) {
    console.warn('[StampHistoryTable] clipboard write failed', e)
  }
}
</script>

<template>
  <div class="overflow-x-auto rounded-lg border border-surface-200 dark:border-surface-700">
    <table class="w-full text-sm">
      <thead class="bg-surface-50 dark:bg-surface-800">
        <tr>
          <th class="px-3 py-2 text-left font-medium">
            {{ t('wallet.admin.history.col_pressed_at') }}
          </th>
          <th class="px-3 py-2 text-left font-medium">
            {{ t('wallet.admin.history.col_provider') }}
          </th>
          <th class="px-3 py-2 text-left font-medium">
            {{ t('wallet.admin.history.col_staff') }}
          </th>
          <th class="px-3 py-2 text-right font-medium">
            {{ t('wallet.admin.history.col_delta') }}
          </th>
          <th class="px-3 py-2 text-left font-medium">
            {{ t('wallet.admin.history.col_memo') }}
          </th>
          <th class="px-3 py-2 text-left font-medium">
            {{ t('wallet.admin.history.col_card_id') }}
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="loading">
          <td colspan="6" class="px-3 py-6 text-center text-surface-500">
            {{ t('wallet.admin.history.loading') }}
          </td>
        </tr>
        <tr v-else-if="stamps.length === 0">
          <td colspan="6" class="px-3 py-6 text-center text-surface-500">
            {{ t('wallet.admin.history.empty') }}
          </td>
        </tr>
        <tr
          v-for="s in stamps"
          v-else
          :key="s.id"
          class="border-t border-surface-200 hover:bg-surface-50 dark:border-surface-700 dark:hover:bg-surface-800/50"
        >
          <td class="whitespace-nowrap px-3 py-2 font-mono text-xs">
            {{ formatDateTime(s.pressedAt) }}
          </td>
          <td class="px-3 py-2">
            {{ s.providerDisplayName ?? '—' }}
          </td>
          <td class="px-3 py-2">
            {{ s.pressedByUserDisplayName ?? '—' }}
          </td>
          <td
            class="whitespace-nowrap px-3 py-2 text-right font-mono font-semibold"
            :class="s.delta > 0 ? 'text-green-700 dark:text-green-400' : 'text-red-700 dark:text-red-400'"
          >
            {{ s.delta > 0 ? `+${s.delta}` : s.delta }}
          </td>
          <td class="max-w-[200px] truncate px-3 py-2">
            {{ s.memo ?? '' }}
          </td>
          <td class="whitespace-nowrap px-3 py-2">
            <div class="flex items-center gap-1">
              <code class="rounded bg-surface-100 px-1 py-0.5 text-xs dark:bg-surface-800">
                {{ shortCardId(s.cardId) }}
              </code>
              <button
                type="button"
                class="rounded border border-surface-300 px-1.5 py-0.5 text-xs hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-800"
                :title="t('wallet.admin.history.copy_card_id')"
                @click="copyCardId(s.cardId)"
              >
                {{ copiedCardId === s.cardId
                  ? t('wallet.admin.actions.copied')
                  : t('wallet.admin.actions.copy')
                }}
              </button>
            </div>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
