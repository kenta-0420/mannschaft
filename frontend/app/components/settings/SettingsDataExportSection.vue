<script setup lang="ts">
import type { DataExportResponse } from '~/composables/useGdprApi'

const { requestDataExport, getExportStatus, getExportDownloadUrl } = useGdprApi()
const notification = useNotification()

const exportStatus = ref<DataExportResponse | null>(null)
const exporting = ref(false)
let pollInterval: ReturnType<typeof setInterval> | null = null

function getStatusLabel(status: DataExportResponse): string {
  const step = status.currentStep?.toLowerCase() ?? ''
  if (step === 'completed' || status.progressPercent === 100) {
    return `エクスポート完了 — ダウンロード期限: ${formatExpiry(status.expiresAt)}`
  }
  if (step === 'failed') {
    return 'エクスポートに失敗しました'
  }
  if (status.progressPercent > 0) {
    return 'エクスポート中...'
  }
  return '準備中...'
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
  return new Date(dateStr).toLocaleString('ja-JP')
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
    notification.error('エクスポート状態の取得に失敗しました')
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
    notification.error('エクスポートのリクエストに失敗しました')
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
      notification.error('ダウンロードURLの取得に失敗しました')
    }
  } catch {
    notification.error('ダウンロードURLの取得に失敗しました')
  }
}

onUnmounted(() => {
  stopPolling()
})
</script>

<template>
  <SectionCard title="データエクスポート">
    <div class="space-y-4">
      <p class="text-sm text-surface-500">
        あなたの個人データをZIPファイルとしてエクスポートできます。
      </p>

      <Button
        label="個人データをエクスポート"
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
              value="完了"
              severity="success"
              class="text-xs"
            />
            <Tag
              v-else-if="isFailed(exportStatus)"
              value="失敗"
              severity="danger"
              class="text-xs"
            />
            <Tag
              v-else
              value="処理中"
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
              ファイルサイズ:
              {{ exportStatus.fileSizeBytes ? (exportStatus.fileSizeBytes / 1024).toFixed(1) + ' KB' : '-' }}
            </p>
            <Button
              label="ダウンロード"
              icon="pi pi-file-export"
              size="small"
              @click="downloadExport"
            />
          </div>

          <p v-if="isFailed(exportStatus)" class="mt-2 text-sm text-red-500">
            エクスポートに失敗しました。再度お試しください。
          </p>
        </div>
      </div>
    </div>
  </SectionCard>
</template>
