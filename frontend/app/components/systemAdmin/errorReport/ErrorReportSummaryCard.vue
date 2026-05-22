<script setup lang="ts">
import type { ErrorReportDetail } from '~/types/error-report'

defineProps<{
  report: ErrorReportDetail
}>()

const { t } = useI18n()
const { formatDateTime } = useDatetime()

function formatDate(value: string | null): string {
  if (!value) return '-'
  return formatDateTime(value)
}

function severityClass(severity: string): string {
  switch (severity) {
    case 'CRITICAL':
      return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300'
    case 'HIGH':
      return 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300'
    case 'MEDIUM':
      return 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-300'
    default:
      return 'bg-surface-100 text-surface-600 dark:bg-surface-700 dark:text-surface-300'
  }
}
</script>

<template>
  <section
    class="rounded-xl border border-surface-300 bg-surface-0 p-5 dark:border-surface-600 dark:bg-surface-800"
  >
    <header class="mb-4 flex items-center gap-3">
      <span class="font-mono text-sm text-surface-500">#{{ report.id }}</span>
      <span
        class="rounded-full px-2 py-0.5 text-xs font-semibold"
        :class="severityClass(report.severity)"
      >
        {{ t(`error_report.severity_label.${report.severity}`) }}
      </span>
      <span
        class="rounded-full bg-surface-100 px-2 py-0.5 text-xs text-surface-700 dark:bg-surface-700 dark:text-surface-200"
      >
        {{ t(`error_report.status.${report.status}`) }}
      </span>
    </header>

    <h3 class="mb-3 break-words text-base font-semibold">{{ report.errorMessage }}</h3>

    <dl class="grid grid-cols-2 gap-x-4 gap-y-2 text-sm sm:grid-cols-4">
      <div>
        <dt class="text-xs text-surface-500">{{ t('error_report.detail.occurrence_count') }}</dt>
        <dd class="font-mono">{{ report.occurrenceCount }}</dd>
      </div>
      <div>
        <dt class="text-xs text-surface-500">{{ t('error_report.detail.affected_users') }}</dt>
        <dd class="font-mono">{{ report.affectedUserCount }}</dd>
      </div>
      <div>
        <dt class="text-xs text-surface-500">{{ t('error_report.detail.first_occurred') }}</dt>
        <dd class="font-mono text-xs">{{ formatDate(report.firstOccurredAt) }}</dd>
      </div>
      <div>
        <dt class="text-xs text-surface-500">{{ t('error_report.detail.last_occurred') }}</dt>
        <dd class="font-mono text-xs">{{ formatDate(report.lastOccurredAt) }}</dd>
      </div>
      <div class="col-span-2 sm:col-span-4">
        <dt class="text-xs text-surface-500">{{ t('error_report.detail.page_url') }}</dt>
        <dd class="break-all font-mono text-xs">{{ report.pageUrl }}</dd>
      </div>
    </dl>
  </section>
</template>
