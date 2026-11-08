<script setup lang="ts">
import type { DataExportResponse } from '~/composables/useGdprApi'
import { getApiErrorCode } from '~/utils/loadError'

const { requestDataExport, getExportStatus, getExportDownloadUrl } = useGdprApi()
const notification = useNotification()
const { formatDateTime } = useDatetime()
const { t } = useI18n()

const exportStatus = ref<DataExportResponse | null>(null)
const exporting = ref(false)
const fetching = ref(false)
const fetchError = shallowRef<unknown>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null
let disposed = false

function getStatusLabel(status: DataExportResponse): string {
  if (isFailed(status)) return t('data_export.step_failed')
  if (isCompleted(status)) {
    return t('data_export.step_completed', { expiry: formatExpiry(status.expiresAt) })
  }
  if (status.progressPercent > 0) {
    return t('data_export.step_processing')
  }
  return t('data_export.step_preparing')
}

function getProgressValue(status: DataExportResponse): number {
  return status.progressPercent ?? 0
}

function isCompleted(status: DataExportResponse): boolean {
  return status.status === 'COMPLETED'
}

function isFailed(status: DataExportResponse): boolean {
  return status.status === 'FAILED'
}

function isFinished(status: DataExportResponse): boolean {
  return isCompleted(status) || isFailed(status)
}

function formatExpiry(dateStr: string | null): string {
  if (!dateStr) return '-'
  return formatDateTime(dateStr)
}

async function pollStatus() {
  if (disposed || fetching.value) return
  stopPolling()
  fetching.value = true
  fetchError.value = null
  try {
    const res = await getExportStatus()
    if (disposed) return
    if (!res?.data) throw new Error('Missing export status')
    exportStatus.value = res.data
    if (!isFinished(res.data)) pollTimer = setTimeout(pollStatus, 10000)
  } catch (error) {
    if (!disposed && getApiErrorCode(error) !== 'GDPR_003') fetchError.value = error
  } finally {
    fetching.value = false
  }
}

function stopPolling() {
  if (pollTimer !== null) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

async function startExport() {
  if (disposed || exporting.value || fetching.value) return
  exporting.value = true
  stopPolling()
  try {
    await requestDataExport({})
    if (!disposed) await pollStatus()
  } catch (error) {
    if (disposed) return
    const code = getApiErrorCode(error)
    const key = code === 'GDPR_001' ? 'request_daily_limit'
      : code === 'GDPR_002' ? 'request_in_progress' : 'request_error'
    notification.error(t(`data_export.${key}`))
    if (code === 'GDPR_002') await pollStatus()
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
      notification.error(t('data_export.download_error'))
    }
  } catch (error) {
    const code = getApiErrorCode(error)
    const key = code === 'GDPR_009' ? 'download_not_ready'
      : code === 'GDPR_010' ? 'download_expired' : 'download_error'
    notification.error(t(`data_export.${key}`))
  }
}

onMounted(pollStatus)
onUnmounted(() => {
  disposed = true
  stopPolling()
})
</script>

<template>
  <SectionCard :title="$t('data_export.section_title')">
    <div class="space-y-4">
      <p class="text-sm text-surface-500">
        {{ $t('data_export.description') }}
      </p>

      <Button
        translate="no"
        :label="$t('data_export.export_button')"
        icon="pi pi-download"
        class="min-h-11 min-w-11"
        :loading="exporting"
        :disabled="exporting || fetching || (exportStatus !== null && !isFinished(exportStatus))"
        @click="startExport"
      />

      <DashboardErrorState
        v-if="fetchError"
        :error="fetchError"
        :message="$t('data_export.fetch_error')"
        :show-retry="true"
        class="[&_button]:min-h-11 [&_button]:min-w-11"
        @retry="pollStatus"
      />

      <div v-if="exportStatus" class="space-y-3">
        <div class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
          <div class="mb-2 flex items-center justify-between">
            <span class="text-sm font-medium">{{ getStatusLabel(exportStatus) }}</span>
            <Tag
              v-if="isCompleted(exportStatus)"
              :value="$t('data_export.status_completed')"
              severity="success"
              class="text-xs"
            />
            <Tag
              v-else-if="isFailed(exportStatus)"
              :value="$t('data_export.status_failed')"
              severity="danger"
              class="text-xs"
            />
            <Tag
              v-else
              :value="$t('data_export.status_processing')"
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
              :label="$t('data_export.download_button')"
              icon="pi pi-file-export"
              size="small"
              class="min-h-11 min-w-11"
              @click="downloadExport"
            />
          </div>

          <p v-if="isFailed(exportStatus)" class="mt-2 text-sm text-red-500">
            {{ $t('data_export.step_failed') }}
          </p>
        </div>
      </div>
    </div>
  </SectionCard>
</template>
