<script setup lang="ts">
import type { ErrorReportStatsResponse } from '~/types/system-admin'

defineProps<{
  stats: ErrorReportStatsResponse | null
}>()
</script>

<template>
  <section class="mb-6">
    <h2
      class="mb-3 flex items-center gap-2 text-sm font-semibold uppercase tracking-wider text-surface-400"
    >
      <i class="pi pi-exclamation-triangle" />エラーレポート
    </h2>
    <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
      <div
        class="flex flex-col rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
        :class="
          (stats?.totalNew ?? 0) > 0
            ? 'border-red-200 bg-red-50 dark:border-red-800 dark:bg-red-900/20'
            : ''
        "
      >
        <span class="mb-1 text-xs text-surface-500">新規</span>
        <span
          class="text-2xl font-bold"
          :class="
            (stats?.totalNew ?? 0) > 0
              ? 'text-red-600'
              : 'text-surface-700 dark:text-surface-200'
          "
        >
          {{ stats?.totalNew ?? '-' }}
        </span>
      </div>
      <div
        class="flex flex-col rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
      >
        <span class="mb-1 text-xs text-surface-500">調査中</span>
        <span class="text-2xl font-bold text-yellow-600">{{
          stats?.totalInvestigating ?? '-'
        }}</span>
      </div>
      <div
        class="flex flex-col rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
        :class="
          (stats?.totalReopened ?? 0) > 0
            ? 'border-orange-200 bg-orange-50 dark:border-orange-800 dark:bg-orange-900/20'
            : ''
        "
      >
        <span class="mb-1 text-xs text-surface-500">再オープン</span>
        <span
          class="text-2xl font-bold"
          :class="
            (stats?.totalReopened ?? 0) > 0
              ? 'text-orange-600'
              : 'text-surface-700 dark:text-surface-200'
          "
        >
          {{ stats?.totalReopened ?? '-' }}
        </span>
      </div>
      <div
        class="flex flex-col rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
      >
        <span class="mb-1 text-xs text-surface-500">今日の件数</span>
        <span class="text-2xl font-bold text-surface-700 dark:text-surface-200">{{
          stats?.totalToday ?? '-'
        }}</span>
      </div>
    </div>

    <div
      v-if="stats?.topErrors && stats.topErrors.length > 0"
      class="mt-3 rounded-xl border border-surface-300 bg-surface-0 dark:border-surface-600 dark:bg-surface-800"
    >
      <div class="border-b border-surface-100 px-4 py-2.5 dark:border-surface-600">
        <span class="text-xs font-semibold text-surface-500"
          >頻出エラー TOP {{ stats.topErrors.length }}</span
        >
      </div>
      <div class="divide-y divide-surface-100 dark:divide-surface-700">
        <NuxtLink
          v-for="err in stats.topErrors"
          :key="err.errorHash"
          to="/admin/reports"
          class="flex items-center justify-between px-4 py-2.5 text-sm hover:bg-surface-50 dark:hover:bg-surface-700"
        >
          <span class="min-w-0 flex-1 truncate text-surface-600 dark:text-surface-300">{{
            err.errorMessage
          }}</span>
          <span
            class="ml-3 shrink-0 rounded-full bg-red-100 px-2 py-0.5 text-xs font-semibold text-red-600 dark:bg-red-900/30 dark:text-red-400"
          >
            {{ err.count }}件
          </span>
        </NuxtLink>
      </div>
    </div>
  </section>
</template>
