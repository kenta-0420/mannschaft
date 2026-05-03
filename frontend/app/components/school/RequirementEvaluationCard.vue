<script setup lang="ts">
import type { AttendanceRequirementEvaluation, EvaluationStatus } from '~/types/school'

const props = defineProps<{
  evaluation: AttendanceRequirementEvaluation
}>()

const emit = defineEmits<{
  resolve: [evaluation: AttendanceRequirementEvaluation]
}>()

const statusSeverity: Record<EvaluationStatus, 'success' | 'warn' | 'danger' | 'secondary'> = {
  OK: 'success',
  WARNING: 'warn',
  RISK: 'danger',
  VIOLATION: 'danger',
}

const statusIcon: Record<EvaluationStatus, string> = {
  OK: 'pi pi-check-circle',
  WARNING: 'pi pi-exclamation-triangle',
  RISK: 'pi pi-times-circle',
  VIOLATION: 'pi pi-ban',
}
</script>

<template>
  <div
    data-testid="requirement-evaluation-card"
    class="rounded-lg border p-4"
    :class="{
      'border-green-300 bg-green-50 dark:border-green-700 dark:bg-green-950': evaluation.status === 'OK',
      'border-yellow-300 bg-yellow-50 dark:border-yellow-700 dark:bg-yellow-950': evaluation.status === 'WARNING',
      'border-orange-300 bg-orange-50 dark:border-orange-700 dark:bg-orange-950': evaluation.status === 'RISK',
      'border-red-300 bg-red-50 dark:border-red-700 dark:bg-red-950': evaluation.status === 'VIOLATION',
    }"
  >
    <div class="flex items-center justify-between mb-3">
      <div class="flex items-center gap-2">
        <i :class="statusIcon[evaluation.status]" class="text-lg" />
        <Tag
          :value="$t(`school.evaluation.status.${evaluation.status}`)"
          :severity="statusSeverity[evaluation.status]"
        />
      </div>
      <Button
        v-if="evaluation.status === 'VIOLATION' && !evaluation.resolvedAt"
        data-testid="resolve-evaluation-btn"
        :label="$t('school.evaluation.resolve')"
        severity="danger"
        size="small"
        outlined
        @click="emit('resolve', evaluation)"
      />
    </div>

    <div class="grid grid-cols-2 gap-2 text-sm">
      <div>
        <span class="text-surface-500 dark:text-surface-400">{{ $t('school.evaluation.currentAttendanceRate') }}</span>
        <p class="font-semibold">{{ evaluation.currentAttendanceRate.toFixed(1) }}%</p>
      </div>
      <div>
        <span class="text-surface-500 dark:text-surface-400">{{ $t('school.evaluation.remainingAllowedAbsences') }}</span>
        <p class="font-semibold">
          <span v-if="evaluation.remainingAllowedAbsences > 0">
            {{ $t('school.evaluation.remainingDays', { days: evaluation.remainingAllowedAbsences }) }}
          </span>
          <span v-else class="text-red-600 dark:text-red-400">
            {{ $t('school.evaluation.noDaysLeft') }}
          </span>
        </p>
      </div>
    </div>

    <div v-if="evaluation.resolvedAt" class="mt-2 text-xs text-surface-500">
      解消済み: {{ evaluation.resolutionNote }}
    </div>
  </div>
</template>
