<script setup lang="ts">
import type { StudentSummaryResponse } from '~/types/school'

const props = defineProps<{
  summary: StudentSummaryResponse
}>()

const emit = defineEmits<{
  recalculate: []
}>()

const { t } = useI18n()
</script>

<template>
  <div data-testid="student-attendance-summary-card" class="rounded-lg border p-4 space-y-3">
    <div class="flex items-center justify-between">
      <h3 class="font-semibold text-lg">{{ t('school.attendanceSummary.title') }}</h3>
      <button
        data-testid="recalculate-btn"
        class="text-sm text-blue-600 hover:underline"
        @click="emit('recalculate')"
      >
        {{ t('school.attendanceSummary.recalculate') }}
      </button>
    </div>

    <div class="grid grid-cols-2 gap-2 text-sm">
      <div>
        <span class="text-gray-500">{{ t('school.attendanceSummary.attendanceRate') }}</span>
        <span class="ml-2 font-bold text-blue-700">{{ props.summary.attendanceRate.toFixed(1) }}%</span>
      </div>
      <div>
        <span class="text-gray-500">{{ t('school.attendanceSummary.totalSchoolDays') }}</span>
        <span class="ml-2">{{ props.summary.totalSchoolDays }}{{ t('school.attendanceSummary.days') }}</span>
      </div>
      <div>
        <span class="text-gray-500">{{ t('school.attendanceSummary.presentDays') }}</span>
        <span class="ml-2 text-green-600">{{ props.summary.presentDays }}{{ t('school.attendanceSummary.days') }}</span>
      </div>
      <div>
        <span class="text-gray-500">{{ t('school.attendanceSummary.absentDays') }}</span>
        <span class="ml-2 text-red-600">{{ props.summary.absentDays }}{{ t('school.attendanceSummary.days') }}</span>
      </div>
      <div>
        <span class="text-gray-500">{{ t('school.attendanceSummary.lateCount') }}</span>
        <span class="ml-2">{{ props.summary.lateCount }}{{ t('school.attendanceSummary.times') }}</span>
      </div>
      <div>
        <span class="text-gray-500">{{ t('school.attendanceSummary.earlyLeaveCount') }}</span>
        <span class="ml-2">{{ props.summary.earlyLeaveCount }}{{ t('school.attendanceSummary.times') }}</span>
      </div>
    </div>

    <div
      v-if="props.summary.sickBayDays > 0 || props.summary.separateRoomDays > 0 || props.summary.onlineDays > 0 || props.summary.homeLearningDays > 0"
      class="text-sm space-y-1 border-t pt-2"
    >
      <div v-if="props.summary.sickBayDays > 0">
        {{ t('school.attendanceSummary.sickBayDays') }}: {{ props.summary.sickBayDays }}{{ t('school.attendanceSummary.days') }}
      </div>
      <div v-if="props.summary.separateRoomDays > 0">
        {{ t('school.attendanceSummary.separateRoomDays') }}: {{ props.summary.separateRoomDays }}{{ t('school.attendanceSummary.days') }}
      </div>
      <div v-if="props.summary.onlineDays > 0">
        {{ t('school.attendanceSummary.onlineDays') }}: {{ props.summary.onlineDays }}{{ t('school.attendanceSummary.days') }}
      </div>
      <div v-if="props.summary.homeLearningDays > 0">
        {{ t('school.attendanceSummary.homeLearningDays') }}: {{ props.summary.homeLearningDays }}{{ t('school.attendanceSummary.days') }}
      </div>
    </div>
  </div>
</template>
