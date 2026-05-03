<script setup lang="ts">
import type { StudentTimelineResponse, AttendanceStatus } from '~/types/school'

defineProps<{
  timeline: StudentTimelineResponse
}>()

const { t } = useI18n()

function statusLabel(status: AttendanceStatus): string {
  return t(`school.attendance.status.${status}`)
}

function statusClass(status: AttendanceStatus): string {
  switch (status) {
    case 'ATTENDING':
      return 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300 border-green-200 dark:border-green-800'
    case 'PARTIAL':
      return 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-300 border-yellow-200 dark:border-yellow-800'
    case 'ABSENT':
      return 'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300 border-red-200 dark:border-red-800'
    case 'UNDECIDED':
    default:
      return 'bg-surface-100 text-surface-500 dark:bg-surface-800 dark:text-surface-400 border-surface-200 dark:border-surface-700'
  }
}

function dailyStatusClass(status: AttendanceStatus): string {
  switch (status) {
    case 'ATTENDING':
      return 'text-green-700 dark:text-green-300 bg-green-50 dark:bg-green-950 border-green-200 dark:border-green-800'
    case 'PARTIAL':
      return 'text-yellow-700 dark:text-yellow-300 bg-yellow-50 dark:bg-yellow-950 border-yellow-200 dark:border-yellow-800'
    case 'ABSENT':
      return 'text-red-700 dark:text-red-300 bg-red-50 dark:bg-red-950 border-red-200 dark:border-red-800'
    case 'UNDECIDED':
    default:
      return 'text-surface-500 bg-surface-50 dark:bg-surface-800 border-surface-200 dark:border-surface-700'
  }
}
</script>

<template>
  <div class="student-timeline-card rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
    <!-- ヘッダー: 日次ステータス -->
    <div class="px-4 py-3 border-b border-surface-200 dark:border-surface-700">
      <div class="flex items-center justify-between">
        <div>
          <div class="text-xs text-surface-500 mb-0.5">
            {{ $t('school.timeline.date') }}
          </div>
          <div class="font-semibold text-surface-800 dark:text-surface-100">
            {{ timeline.date }}
          </div>
        </div>
        <div class="text-right">
          <div class="text-xs text-surface-500 mb-1">
            {{ $t('school.timeline.dailyStatus') }}
          </div>
          <span
            class="inline-block px-3 py-1 rounded-full text-sm font-medium border"
            :class="dailyStatusClass(timeline.dailyStatus)"
          >
            {{ statusLabel(timeline.dailyStatus) }}
          </span>
        </div>
      </div>
    </div>

    <!-- 時限別ステータス -->
    <div class="p-4">
      <div class="text-xs text-surface-500 mb-3">
        {{ $t('school.timeline.periodStatus') }}
      </div>

      <div v-if="timeline.periods.length === 0" class="text-center text-surface-400 text-sm py-4">
        {{ $t('school.timeline.noPeriods') }}
      </div>

      <div v-else class="flex flex-wrap gap-2">
        <div
          v-for="period in timeline.periods"
          :key="period.periodNumber"
          class="flex flex-col items-center rounded-lg border p-2 min-w-[60px]"
          :class="statusClass(period.status)"
        >
          <div class="text-xs font-bold mb-1">
            {{ period.periodNumber }}
          </div>
          <div class="text-xs font-medium text-center leading-tight">
            {{ statusLabel(period.status) }}
          </div>
          <div
            v-if="period.absenceReason"
            class="text-xs mt-1 opacity-75"
            :title="$t(`school.attendance.absenceReason.${period.absenceReason}`)"
          >
            ({{ $t(`school.attendance.absenceReason.${period.absenceReason}`) }})
          </div>
          <div
            v-if="period.comment"
            class="text-xs mt-1 opacity-75 max-w-[80px] truncate"
            :title="period.comment"
          >
            {{ period.comment }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
