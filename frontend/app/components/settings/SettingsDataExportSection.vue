<script setup lang="ts">
import type { DataExportResponse } from '~/composables/useGdprApi'

const { requestDataExport, getExportStatus, getExportDownloadUrl } = useGdprApi()
const notification = useNotification()
const { formatDateTime } = useDatetime()
const { t } = useI18n()

const exportStatus = ref<DataExportResponse | null>(null)
const exporting = ref(false)
let pollInterval: ReturnType<typeof setInterval> | null = null

function getStatusLabel(status: DataExportResponse): string {
  const step = status.currentStep?.toLowerCase() ?? ''
  if (step === 'completed' || status.progressPercent === 100) {
    return t('settings.data_export.step_completed', { expiry: formatExpiry(status.expiresAt) })
  }
  if (step === 'failed') {
    return t('settings.data_export.step_failed')
  }
  if (status.progressPercent > 0) {
    return t('settings.data_export.step_processing')
  }
  return t('settings.data_export.step_preparing')
}

function getProgressValue(status: DataExportResponse): number {
  return status.progressPercent ?? 0
}

function isCompleted(status: DataExportResponse): boolean {
  return (
    status.currentStep?.toLowerCase() === 'completed' || status.progressPercent === 100
  )
}

function isFailed(status: DataExportResponse): boolean {
  return status.currentStep?.toLowerCase() === 'failed'
}

function isFinished(status: DataExportResponse): boolean {
  return isCompleted(status) || isFailed(status)
}

function formatExpiry(dateStr: string): string {
  if (!dateStr) return '-'
  return formatDateTime(dateStr)
}

async function pollStatus() {
  try {
    const res = await getExportStatus()
    exportStatus.value = res?.data ?? null
    if (exportStatus.value && isFinished(exportStatus.value)) {
      stopPolling()
    }
  } catch {
    stopPolling()
    notification.error(t('settings.data_export.fetch_error'))
  }
}

function stopPolling() {
  if (pollInterval !== null) {
    clearInterval(pollInterval)
    pollInterval = null
  }
}

async function startExport() {
  if (exporting.value) return
  exporting.value = true
  exportStatus.value = null
  stopPolling()
  try {
    await requestDataExport({})
    await pollStatus()
    if (exportStatus.value && !isFinished(exportStatus.value)) {
      pollInterval = setInterval(pollStatus, 10000)
    }
  } catch {
    notification.error(t('settings.data_export.fetch_error'))
  } finally {
    exporting.value = false
  }
}

async function downloadExport() {
  try {
    const res = await getExportDownloadUrl()
    const url = res?.data?.url ?? res?.data?.downloadUrl
    if (url) {
      window.open(url, '_blank')
    } else {
      notification.error(t('settings.data_export.fetch_error'))
    }
  } catch {
    notification.error(t('settings.data_export.fetch_error'))
  }
}

onUnmounted(() => {
  stopPolling()
})
</script>

<template>
  <SectionCard :title="$t('settings.data_export.section_title')">
    <div class="space-y-4">
      <p class="text-sm text-surface-500">
        {{ $t('settings.data_export.description') }}
      </p>

      <Button
        translate="no"
        :label="$t('settings.data_export.export_button')"
        icon="pi pi-download"
        :loading="exporting"
        :disabled="exporting || (exportStatus !== null && !isFinished(exportStatus))"
        @click="startExport"
      />

      <div v-if="exportStatus" class="space-y-3">
        <div class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
          <div class="mb-2 flex items-center justify-between">
            <span class="text-sm font-medium">{{ getStatusLabel(exportStatus) }}</span>
            <Tag
              v-if="isCompleted(exportStatus)"
              :value="$t('settings.data_export.status_completed')"
              severity="success"
              class="text-xs"
            />
            <Tag
              v-else-if="isFailed(exportStatus)"
              :value="$t('settings.data_export.status_failed')"
              severity="danger"
              class="text-xs"
            />
            <Tag
              v-else
              :value="$t('settings.data_export.status_processing')"
              severity="info"
              class="text-xs"
            />
          </div>

          <ProgressBar
            :value="getProgressValue(exportStatus)"
            :show-value="true"
            class="mb-2 h-3"
          />

          <div v-if="isCompleted(exportStatus)" class="mt-3 space-y-2">
            <p class="text-xs text-surface-500">
              {{ exportStatus.fileSizeBytes != null ? (exportStatus.fileSizeBytes / 1024).toFixed(1) + ' KB' : '-' }}
            </p>
            <Button
              translate="no"
              :label="$t('settings.data_export.download_button')"
              icon="pi pi-file-export"
              size="small"
              @click="downloadExport"
            />
          </div>

          <p v-if="isFailed(exportStatus)" class="mt-2 text-sm text-red-500">
            {{ $t('settings.data_export.step_failed') }}
          </p>
        </div>
      </div>
    </div>
  </SectionCard>
</template>
