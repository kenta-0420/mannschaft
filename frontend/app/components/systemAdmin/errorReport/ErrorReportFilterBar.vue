<script setup lang="ts">
import type { ErrorReportSeverity, ErrorReportStatus } from '~/types/error-report'

interface FilterState {
  status: ErrorReportStatus | undefined
  severity: ErrorReportSeverity | undefined
  keyword: string
  from: string
  to: string
}

const props = defineProps<{
  modelValue: FilterState
}>()

const emit = defineEmits<{
  'update:modelValue': [value: FilterState]
  'apply': []
  'clear': []
}>()

const { t } = useI18n()

const statusOptions = computed(() => [
  { label: t('error_report.filters.all'), value: undefined },
  { label: t('error_report.status.NEW'), value: 'NEW' as const },
  { label: t('error_report.status.INVESTIGATING'), value: 'INVESTIGATING' as const },
  { label: t('error_report.status.RESOLVED'), value: 'RESOLVED' as const },
  { label: t('error_report.status.REOPENED'), value: 'REOPENED' as const },
  { label: t('error_report.status.IGNORED'), value: 'IGNORED' as const },
])

const severityOptions = computed(() => [
  { label: t('error_report.filters.all'), value: undefined },
  { label: t('error_report.severity_label.LOW'), value: 'LOW' as const },
  { label: t('error_report.severity_label.MEDIUM'), value: 'MEDIUM' as const },
  { label: t('error_report.severity_label.HIGH'), value: 'HIGH' as const },
  { label: t('error_report.severity_label.CRITICAL'), value: 'CRITICAL' as const },
])

function update<K extends keyof FilterState>(key: K, value: FilterState[K]) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}
</script>

<template>
  <div
    class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
  >
    <div class="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5">
      <div class="flex flex-col gap-1">
        <label class="text-xs text-surface-500">{{ t('error_report.filters.status') }}</label>
        <Select
          :model-value="modelValue.status"
          :options="statusOptions"
          option-label="label"
          option-value="value"
          class="w-full"
          @update:model-value="(v) => update('status', v)"
        />
      </div>
      <div class="flex flex-col gap-1">
        <label class="text-xs text-surface-500">{{ t('error_report.filters.severity') }}</label>
        <Select
          :model-value="modelValue.severity"
          :options="severityOptions"
          option-label="label"
          option-value="value"
          class="w-full"
          @update:model-value="(v) => update('severity', v)"
        />
      </div>
      <div class="flex flex-col gap-1">
        <label class="text-xs text-surface-500">{{ t('error_report.filters.from') }}</label>
        <InputText
          :model-value="modelValue.from"
          type="date"
          class="w-full"
          @update:model-value="(v) => update('from', String(v ?? ''))"
        />
      </div>
      <div class="flex flex-col gap-1">
        <label class="text-xs text-surface-500">{{ t('error_report.filters.to') }}</label>
        <InputText
          :model-value="modelValue.to"
          type="date"
          class="w-full"
          @update:model-value="(v) => update('to', String(v ?? ''))"
        />
      </div>
      <div class="flex items-end gap-2">
        <Button
          :label="t('error_report.filters.clear')"
          severity="secondary"
          outlined
          @click="emit('clear')"
        />
        <Button :label="t('common.search')" icon="pi pi-search" @click="emit('apply')" />
      </div>
    </div>
  </div>
</template>
