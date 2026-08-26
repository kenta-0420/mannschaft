<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const {
  reports,
  stats,
  loading,
  totalRecords,
  page,
  statusFilter,
  selectedReport,
  showDetailDialog,
  notes,
  newNote,
  showResolveDialog,
  resolveForm,
  showEscalateDialog,
  escalateForm,
  statusOptions,
  openDetail,
  addNote,
  review,
  openResolve,
  dismiss,
  openEscalate,
  escalate,
  reopen,
  hideContent,
  restoreContent,
  statusSeverity,
  onPage,
  resolve,
} = useAdminReports()
const { formatDateTime } = useDatetime()
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="$t('admin_report.title')" />
      <Select
        v-model="statusFilter"
        :options="statusOptions"
        option-label="label"
        option-value="value"
        :placeholder="$t('admin_report.table.status_placeholder')"
        class="w-48"
      />
    </div>

    <AdminReportStatsCards v-if="stats" :stats="stats" />

    <DataTable
      :value="reports"
      :loading="loading"
      :lazy="true"
      :paginator="true"
      :rows="20"
      :total-records="totalRecords"
      :first="page * 20"
      data-key="id"
      striped-rows
      @page="onPage"
    >
      <template #empty>
        <div class="py-8 text-center text-surface-500">{{ $t('admin_report.empty') }}</div>
      </template>
      <Column header="ID" style="width: 60px">
        <template #body="{ data }">
          <span class="text-xs text-surface-500">#{{ data.id }}</span>
        </template>
      </Column>
      <Column :header="$t('admin_report.table.status')" style="width: 120px">
        <template #body="{ data }">
          <Tag :value="data.status" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column field="targetType" :header="$t('admin_report.table.target_type')" style="width: 100px" />
      <Column field="reason" :header="$t('admin_report.table.reason')" />
      <Column :header="$t('admin_report.table.reported_at')" style="width: 140px">
        <template #body="{ data }">
          <span class="text-sm">{{ formatDateTime(data.createdAt) }}</span>
        </template>
      </Column>
      <Column :header="$t('admin_report.table.actions')" style="width: 300px">
        <template #body="{ data }">
          <div class="flex flex-wrap gap-1">
            <Button
              v-if="data.status === 'PENDING'"
              :label="$t('admin_report.actions.review')"
              size="small"
              @click="review(data.id)"
            />
            <Button
              v-if="data.status === 'REVIEWING'"
              :label="$t('admin_report.actions.resolve')"
              size="small"
              severity="success"
              @click="openResolve(data)"
            />
            <Button
              v-if="data.status === 'REVIEWING'"
              :label="$t('admin_report.actions.dismiss')"
              size="small"
              severity="secondary"
              @click="dismiss(data.id)"
            />
            <Button
              v-if="data.status === 'REVIEWING'"
              :label="$t('admin_report.actions.escalate')"
              size="small"
              severity="warn"
              @click="openEscalate(data)"
            />
            <Button
              v-if="data.status === 'RESOLVED' || data.status === 'DISMISSED'"
              :label="$t('admin_report.actions.reopen')"
              size="small"
              severity="info"
              @click="reopen(data.id)"
            />
            <Button icon="pi pi-eye" size="small" severity="info" text @click="openDetail(data)" />
          </div>
        </template>
      </Column>
    </DataTable>

    <AdminReportDetailDialog
      v-model:visible="showDetailDialog"
      v-model:new-note="newNote"
      :report="selectedReport"
      :notes="notes"
      :status-severity="statusSeverity"
      @add-note="addNote"
      @hide-content="hideContent"
      @restore-content="restoreContent"
    />

    <AdminReportResolveDialog
      v-model:visible="showResolveDialog"
      v-model:form="resolveForm"
      @resolve="resolve"
    />

    <AdminReportEscalateDialog
      v-model:visible="showEscalateDialog"
      v-model:form="escalateForm"
      @escalate="escalate"
    />
  </div>
</template>
