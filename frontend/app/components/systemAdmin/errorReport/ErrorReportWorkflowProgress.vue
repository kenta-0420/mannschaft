<script setup lang="ts">
import type { ErrorReportStatus, WorkflowStage } from '~/types/error-report'

const props = defineProps<{
  status: ErrorReportStatus
  workflowStage: WorkflowStage | null
  loading?: boolean
}>()

const emit = defineEmits<{
  change: [stage: WorkflowStage | null]
}>()

const { t } = useI18n()

interface StageDef {
  value: WorkflowStage | null
  label: string
}

// status により選択可能な工程を絞り込む
const availableStages = computed<StageDef[]>(() => {
  const all: StageDef[] = [
    { value: null, label: t('error_report.stage.null') },
    { value: 'INVESTIGATION_STARTED', label: t('error_report.stage.INVESTIGATION_STARTED') },
    { value: 'ROOT_CAUSE_IDENTIFIED', label: t('error_report.stage.ROOT_CAUSE_IDENTIFIED') },
    { value: 'FIX_IN_PROGRESS', label: t('error_report.stage.FIX_IN_PROGRESS') },
    { value: 'TEST_COMPLETED', label: t('error_report.stage.TEST_COMPLETED') },
    { value: 'RELEASED', label: t('error_report.stage.RELEASED') },
  ]

  switch (props.status) {
    case 'NEW':
    case 'IGNORED':
    case 'REOPENED':
      return [all[0]!]
    case 'INVESTIGATING':
      return [all[0]!, all[1]!, all[2]!, all[3]!]
    case 'RESOLVED':
      return [all[4]!, all[5]!]
    default:
      return all
  }
})

const orderedStages: WorkflowStage[] = [
  'INVESTIGATION_STARTED',
  'ROOT_CAUSE_IDENTIFIED',
  'FIX_IN_PROGRESS',
  'TEST_COMPLETED',
  'RELEASED',
]

function progressIndex(stage: WorkflowStage | null): number {
  if (stage === null) return -1
  return orderedStages.indexOf(stage)
}

function isCompleted(stage: WorkflowStage): boolean {
  return progressIndex(props.workflowStage) >= orderedStages.indexOf(stage)
}

function onStageChange(value: WorkflowStage | null) {
  emit('change', value)
}
</script>

<template>
  <section class="space-y-3">
    <div class="flex items-center justify-between gap-3">
      <span class="text-xs font-semibold uppercase tracking-wider text-surface-500">
        {{ t('error_report.actions.change_workflow') }}
      </span>
      <Select
        :model-value="workflowStage"
        :options="availableStages"
        option-label="label"
        option-value="value"
        :disabled="loading"
        class="w-48"
        @update:model-value="onStageChange"
      />
    </div>

    <div class="flex flex-wrap gap-2">
      <div
        v-for="stage in orderedStages"
        :key="stage"
        class="flex flex-1 flex-col items-center gap-1 rounded-md border px-2 py-2 text-center text-xs"
        :class="
          isCompleted(stage)
            ? 'border-blue-300 bg-blue-50 text-blue-700 dark:border-blue-700 dark:bg-blue-900/30 dark:text-blue-200'
            : 'border-surface-300 bg-surface-50 text-surface-500 dark:border-surface-600 dark:bg-surface-800'
        "
      >
        <i v-if="isCompleted(stage)" class="pi pi-check-circle" />
        <i v-else class="pi pi-circle" />
        <span class="text-[11px]">{{ t(`error_report.stage.${stage}`) }}</span>
      </div>
    </div>
  </section>
</template>
