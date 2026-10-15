<script setup lang="ts">
import type { BatchJobLogResponse } from '~/types/system-admin'

defineProps<{
  logs: BatchJobLogResponse[]
}>()

const { relativeTime } = useRelativeTime()

function batchStatusClass(status: string) {
  switch (status) {
    case 'SUCCESS':
      return 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
    case 'FAILED':
      return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400'
    case 'RUNNING':
      return 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400'
    default:
      return 'bg-surface-100 text-surface-500'
  }
}

function batchStatusLabel(status: string) {
  switch (status) {
    case 'SUCCESS':
      return '成功'
    case 'FAILED':
      return '失敗'
    case 'RUNNING':
      return '実行中'
    default:
      return status
  }
}
</script>

<template>
  <div
    class="rounded-xl border border-surface-300 bg-surface-0 dark:border-surface-600 dark:bg-surface-800"
  >
    <div
      class="flex items-center justify-between border-b border-surface-100 px-4 py-3 dark:border-surface-600"
    >
      <span class="text-sm font-semibold">バッチジョブ（直近）</span>
    </div>
    <div v-if="logs.length > 0" class="divide-y divide-surface-100 dark:divide-surface-700">
      <div v-for="log in logs" :key="log.id" class="px-4 py-3">
        <div class="flex items-center justify-between gap-2">
          <p
            class="min-w-0 flex-1 truncate text-sm font-medium text-surface-700 dark:text-surface-200"
          >
            {{ log.jobName }}
          </p>
          <span
            :class="batchStatusClass(log.status)"
            class="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-medium"
          >
            {{ batchStatusLabel(log.status) }}
          </span>
        </div>
        <div class="mt-0.5 flex items-center gap-2 text-[11px] text-surface-400">
          <span>{{ relativeTime(log.startedAt) }}</span>
          <span v-if="log.processedCount > 0">・{{ log.processedCount }}件処理</span>
        </div>
        <p v-if="log.errorMessage" class="mt-1 line-clamp-1 text-xs text-red-500">
          {{ log.errorMessage }}
        </p>
      </div>
    </div>
    <div v-else class="px-4 py-8 text-center text-sm text-surface-400">
      <i class="pi pi-inbox mb-2 text-2xl" />
      <p>バッチログがありません</p>
    </div>
  </div>
</template>
