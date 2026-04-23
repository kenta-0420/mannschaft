<script setup lang="ts">
import type { ShiftScheduleResponse, ShiftRequestSummaryResponse } from '~/types/shift'

const props = defineProps<{
  schedule: ShiftScheduleResponse
  summary?: ShiftRequestSummaryResponse | null
}>()

const emit = defineEmits<{
  click: [id: number]
}>()

const { t } = useI18n()

/** 提出率（%）: submittedCount / totalMembers */
const submissionRate = computed<number>(() => {
  if (!props.summary || props.summary.totalMembers === 0) return 0
  return Math.round((props.summary.submittedCount / props.summary.totalMembers) * 100)
})

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString('ja-JP', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}
</script>

<template>
  <div
    class="cursor-pointer rounded-xl border border-surface-200 bg-surface-0 p-4 transition-shadow hover:shadow-md dark:border-surface-700 dark:bg-surface-900"
    @click="emit('click', schedule.id)"
  >
    <!-- ヘッダー行 -->
    <div class="mb-2 flex items-start justify-between gap-2">
      <h3 class="min-w-0 flex-1 truncate font-semibold text-surface-800 dark:text-surface-100">
        {{ schedule.title }}
      </h3>
      <ShiftStatusBadge :status="schedule.status" />
    </div>

    <!-- 期間 -->
    <p class="mb-3 text-xs text-surface-500">
      {{ formatDate(schedule.startDate) }} 〜 {{ formatDate(schedule.endDate) }}
    </p>

    <!-- 提出率 -->
    <div v-if="summary" class="flex items-center gap-2">
      <div class="h-1.5 flex-1 rounded-full bg-surface-200 dark:bg-surface-700">
        <div
          class="h-1.5 rounded-full bg-primary transition-all"
          :style="{ width: `${submissionRate}%` }"
        />
      </div>
      <span class="shrink-0 text-xs text-surface-500">
        {{ t('shift.index.submissionRate', { rate: submissionRate }) }}
      </span>
    </div>

    <!-- 締切 -->
    <p v-if="schedule.requestDeadline" class="mt-2 text-xs text-surface-400">
      {{ t('shift.index.deadline') }}: {{ formatDate(schedule.requestDeadline) }}
    </p>
  </div>
</template>
