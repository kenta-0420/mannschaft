<script setup lang="ts">
import type { AtRiskStudentResponse, EvaluationStatus } from '~/types/school'

const props = defineProps<{
  students: AtRiskStudentResponse[]
  loading?: boolean
}>()

const emit = defineEmits<{
  resolve: [student: AtRiskStudentResponse]
}>()

const statusSeverity: Record<EvaluationStatus, 'warn' | 'danger'> = {
  OK: 'warn',
  WARNING: 'warn',
  RISK: 'danger',
  VIOLATION: 'danger',
}
</script>

<template>
  <div data-testid="at-risk-student-list">
    <div v-if="loading" class="text-center py-4 text-surface-500">
      {{ $t('common.loading') }}
    </div>
    <div v-else-if="students.length === 0" class="text-center py-4 text-surface-500">
      {{ $t('school.evaluation.noAtRisk') }}
    </div>
    <DataTable
      v-else
      :value="students"
      size="small"
      striped-rows
    >
      <Column field="studentUserId" :header="$t('school.homeroom.title')" />
      <Column :header="$t('school.evaluation.title')">
        <template #body="{ data }">
          <Tag
            :value="$t(`school.evaluation.status.${data.status}`)"
            :severity="statusSeverity[data.status]"
          />
        </template>
      </Column>
      <Column :header="$t('school.evaluation.currentAttendanceRate')">
        <template #body="{ data }">
          {{ data.currentAttendanceRate.toFixed(1) }}%
        </template>
      </Column>
      <Column :header="$t('school.evaluation.remainingAllowedAbsences')">
        <template #body="{ data }">
          <span :class="data.remainingAllowedAbsences <= 0 ? 'text-red-500 font-bold' : ''">
            {{ data.remainingAllowedAbsences }}
          </span>
        </template>
      </Column>
      <Column>
        <template #body="{ data }">
          <Button
            v-if="data.status === 'VIOLATION'"
            data-testid="at-risk-resolve-btn"
            :label="$t('school.evaluation.resolve')"
            severity="danger"
            size="small"
            text
            @click="emit('resolve', data)"
          />
        </template>
      </Column>
    </DataTable>
  </div>
</template>
