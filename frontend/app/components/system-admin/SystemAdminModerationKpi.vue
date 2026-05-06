<script setup lang="ts">
import type { ModerationDashboardResponse } from '~/types/system-admin'

const props = defineProps<{
  stats: ModerationDashboardResponse | null
}>()

interface KpiCard {
  label: string
  key: keyof ModerationDashboardResponse
  alertColor: string
  alertBorder: string
  alertBg: string
  alertText: string
}

const cards: KpiCard[] = [
  {
    label: '未対応通報',
    key: 'pendingReportsCount',
    alertColor: 'text-red-600',
    alertBorder: 'border-red-200 dark:border-red-800',
    alertBg: 'bg-red-50 dark:bg-red-900/20',
    alertText: 'text-red-600',
  },
  {
    label: '異議申立',
    key: 'pendingAppealsCount',
    alertColor: 'text-orange-600',
    alertBorder: 'border-orange-200 dark:border-orange-800',
    alertBg: 'bg-orange-50 dark:bg-orange-900/20',
    alertText: 'text-orange-600',
  },
  {
    label: '再審査待ち',
    key: 'pendingReReviewsCount',
    alertColor: 'text-yellow-600',
    alertBorder: 'border-yellow-200 dark:border-yellow-800',
    alertBg: 'bg-yellow-50 dark:bg-yellow-900/20',
    alertText: 'text-yellow-600',
  },
  {
    label: 'エスカレーション',
    key: 'escalatedReReviewsCount',
    alertColor: 'text-red-600',
    alertBorder: 'border-red-200 dark:border-red-800',
    alertBg: 'bg-red-50 dark:bg-red-900/20',
    alertText: 'text-red-600',
  },
  {
    label: 'フラグ解除申請',
    key: 'pendingUnflagRequestsCount',
    alertColor: 'text-purple-600',
    alertBorder: 'border-purple-200 dark:border-purple-800',
    alertBg: 'bg-purple-50 dark:bg-purple-900/20',
    alertText: 'text-purple-600',
  },
]

function isAlert(card: KpiCard): boolean {
  return (props.stats?.[card.key] ?? 0) > 0
}

function value(card: KpiCard): string | number {
  return props.stats?.[card.key] ?? '-'
}
</script>

<template>
  <section class="mb-6">
    <h2
      class="mb-3 flex items-center gap-2 text-sm font-semibold uppercase tracking-wider text-surface-400"
    >
      <i class="pi pi-shield" />モデレーション状況
    </h2>
    <div class="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-7">
      <div
        v-for="card in cards"
        :key="card.key"
        class="flex flex-col rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
        :class="isAlert(card) ? [card.alertBorder, card.alertBg] : ''"
      >
        <span class="mb-1 text-xs text-surface-500">{{ card.label }}</span>
        <span
          class="text-2xl font-bold"
          :class="isAlert(card) ? card.alertText : 'text-surface-700 dark:text-surface-200'"
        >
          {{ value(card) }}
        </span>
      </div>
      <div
        class="flex flex-col rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
      >
        <span class="mb-1 text-xs text-surface-500">有効違反数</span>
        <span class="text-2xl font-bold text-surface-700 dark:text-surface-200">
          {{ stats?.activeViolationsCount ?? '-' }}
        </span>
      </div>
      <div
        class="flex flex-col rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
        :class="
          (stats?.yabaiUsersCount ?? 0) > 0
            ? 'border-red-200 bg-red-50 dark:border-red-800 dark:bg-red-900/20'
            : ''
        "
      >
        <span class="mb-1 text-xs text-surface-500">ヤバイユーザー</span>
        <span
          class="text-2xl font-bold"
          :class="
            (stats?.yabaiUsersCount ?? 0) > 0
              ? 'text-red-600'
              : 'text-surface-700 dark:text-surface-200'
          "
        >
          {{ stats?.yabaiUsersCount ?? '-' }}
        </span>
      </div>
    </div>
  </section>
</template>
