<script setup lang="ts">
import type { ErrorReportDetail } from '~/types/error-report'
import type { PageMeta } from '~/types/api'

defineProps<{
  reports: ErrorReportDetail[]
  loading: boolean
  meta: PageMeta | null
}>()

const emit = defineEmits<{
  'open': [id: number]
  'page-change': [page: number, size: number]
}>()

const { t } = useI18n()
const { formatDateTime } = useDatetime()
const { slaStatus, slaLabel } = useSlaDueAt()

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

function formatDate(value: string | null): string {
  if (!value) return '-'
  return formatDateTime(value)
}

function onPage(event: { page: number; rows: number }) {
  emit('page-change', event.page, event.rows)
}
</script>

<template>
  <DataTable
    :value="reports"
    :loading="loading"
    striped-rows
    paginator
    :rows="meta?.size ?? 20"
    :total-records="meta?.totalElements ?? 0"
    lazy
    :first="(meta?.page ?? 0) * (meta?.size ?? 20)"
    :rows-per-page-options="[20, 50, 100]"
    data-key="id"
    @page="onPage"
  >
    <template #empty>
      <div class="py-6 text-center text-sm text-surface-500">
        {{ t('error_report.timeline.no_items') }}
      </div>
    </template>
    <Column field="id" :header="t('error_report.table.id')" style="width: 70px">
      <template #body="{ data }">
        <span class="font-mono text-xs">#{{ data.id }}</span>
      </template>
    </Column>
    <Column field="errorMessage" :header="t('error_report.table.message')">
      <template #body="{ data }">
        <span class="line-clamp-2 text-sm">{{ data.errorMessage }}</span>
      </template>
    </Column>
    <Column field="severity" :header="t('error_report.table.severity')" style="width: 100px">
      <template #body="{ data }">
        <span
          class="rounded-full px-2 py-0.5 text-xs font-semibold"
          :class="severityClass(data.severity)"
        >
          {{ t(`error_report.severity_label.${data.severity}`) }}
        </span>
      </template>
    </Column>
    <Column field="status" :header="t('error_report.table.status')" style="width: 100px">
      <template #body="{ data }">
        <span class="text-xs">{{ t(`error_report.status.${data.status}`) }}</span>
      </template>
    </Column>
    <Column field="workflowStage" :header="t('error_report.table.stage')" style="width: 110px">
      <template #body="{ data }">
        <span class="text-xs text-surface-600 dark:text-surface-300">
          {{
            data.workflowStage
              ? t(`error_report.stage.${data.workflowStage}`)
              : t('error_report.stage.null')
          }}
        </span>
      </template>
    </Column>
    <Column field="assigneeName" :header="t('error_report.table.assignee')" style="width: 120px">
      <template #body="{ data }">
        <span class="text-xs">{{ data.assigneeName ?? '-' }}</span>
      </template>
    </Column>
    <Column
      field="occurrenceCount"
      :header="t('error_report.table.occurrences')"
      style="width: 80px"
    >
      <template #body="{ data }">
        <span class="font-mono text-xs">{{ data.occurrenceCount }}</span>
      </template>
    </Column>
    <Column field="affectedUserCount" :header="t('error_report.table.affected')" style="width: 80px">
      <template #body="{ data }">
        <span class="font-mono text-xs">{{ data.affectedUserCount }}</span>
      </template>
    </Column>
    <Column field="slaDueAt" :header="t('error_report.table.sla')" style="width: 110px">
      <template #body="{ data }">
        <ClientOnly>
          <span
            class="text-xs font-mono"
            :class="{
              'text-red-600 font-bold dark:text-red-400': slaStatus(data.slaDueAt) === 'overdue',
              'text-orange-500 dark:text-orange-400': slaStatus(data.slaDueAt) === 'warning',
              'text-surface-500': slaStatus(data.slaDueAt) === 'ok' || slaStatus(data.slaDueAt) === 'none',
            }"
          >
            {{ slaLabel(data.slaDueAt) }}
          </span>
        </ClientOnly>
      </template>
    </Column>
    <Column
      field="lastOccurredAt"
      :header="t('error_report.table.last_occurred')"
      style="width: 160px"
    >
      <template #body="{ data }">
        <span class="font-mono text-xs">{{ formatDate(data.lastOccurredAt) }}</span>
      </template>
    </Column>
    <Column style="width: 100px">
      <template #body="{ data }">
        <Button
          :label="t('error_report.actions.open_detail')"
          size="small"
          text
          icon="pi pi-arrow-right"
          icon-pos="right"
          @click="emit('open', data.id)"
        />
      </template>
    </Column>
  </DataTable>
</template>
