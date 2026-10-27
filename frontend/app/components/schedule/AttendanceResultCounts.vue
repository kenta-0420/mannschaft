<script setup lang="ts">
import type { AttendanceSummary } from '~/utils/attendanceResults'
import { attendanceCounts } from '~/utils/attendanceResults'
defineProps<{ summary: AttendanceSummary }>()
const { t } = useI18n()
</script>

<template>
  <div class="flex flex-wrap gap-x-4 gap-y-1 text-sm" data-testid="attendance-counts">
    <span v-for="(count, key) in attendanceCounts(summary)" :key="key">
      {{ t(`dashboard.attendance_results.status.${key}`) }}
      <strong class="tabular-nums">{{ count }}</strong>
    </span>
  </div>
</template>
