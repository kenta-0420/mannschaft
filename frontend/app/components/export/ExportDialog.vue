<script setup lang="ts">
const props = defineProps<{
  visible: boolean
  scopeType: 'team' | 'organization'
  scopeId: number
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
}>()

const exportApi = useExportApi()
const notification = useNotification()

const format = ref<'CSV' | 'JSON'>('CSV')
const selectedTargets = ref<string[]>([])
const exporting = ref(false)
const jobId = ref<string | null>(null)
const progress = ref(0)
const downloadUrl = ref<string | null>(null)

const targetOptions = [
  { label: 'メンバー一覧', value: 'MEMBERS' },
  { label: '活動記録', value: 'ACTIVITIES' },
  { label: 'スケジュール', value: 'SCHEDULES' },
  { label: 'チャット履歴', value: 'CHATS' },
  { label: '掲示板', value: 'BULLETIN' },
  { label: 'TODO', value: 'TODOS' },
  { label: 'タイムライン', value: 'TIMELINE' },
  { label: 'ファイル一覧', value: 'FILES' },
]

const formatOptions = [
  { label: 'CSV', value: 'CSV' as const },
  { label: 'JSON', value: 'JSON' as const },
]

async function startExport() {
  if (selectedTargets.value.length === 0) return
  exporting.value = true
  progress.value = 0
  downloadUrl.value = null
  try {
    const res = await exportApi.startExport(props.scopeType, props.scopeId, {
      targets: selectedTargets.value,
      format: format.value,
    })
    jobId.value = res.jobId
    pollStatus()
  } catch {
    notification.error('エクスポートの開始に失敗しました')
    exporting.value = false
  }
}

async function pollStatus() {
  if (!jobId.value) return
  try {
    const status = await exportApi.getStatus(jobId.value)
    progress.value = status.progress
    if (status.status === 'COMPLETED') {
      downloadUrl.value = status.downloadUrl
      exporting.value = false
      notification.success('エクスポートが完了しました')
    } else if (status.status === 'FAILED') {
      exporting.value = false
      notification.error('エクスポートに失敗しました')
    } else {
      setTimeout(pollStatus, 2000)
    }
  } catch {
    exporting.value = false
    notification.error('ステータスの確認に失敗しました')
  }
}

function handleDownload() {
  if (downloadUrl.value) {
    window.open(downloadUrl.value, '_blank')
  }
}

function close() {
  emit('update:visible', false)
  selectedTargets.value = []
  jobId.value = null
  progress.value = 0
  downloadUrl.value = null
}
</script>

<template>
  <Dialog :visible="visible" header="データエクスポート" :modal="true" class="w-full max-w-lg" @update:visible="close">
    <div class="space-y-4">
      <div>
        <label class="mb-2 block text-sm font-medium">エクスポート対象</label>
        <div class="grid grid-cols-2 gap-2">
          <div v-for="opt in targetOptions" :key="opt.value" class="flex items-center gap-2">
            <Checkbox v-model="selectedTargets" :input-id="opt.value" :value="opt.value" />
            <label :for="opt.value" class="text-sm">{{ opt.label }}</label>
          </div>
        </div>
      </div>

      <div>
        <label class="mb-2 block text-sm font-medium">フォーマット</label>
        <SelectButton v-model="format" :options="formatOptions" option-label="label" option-value="value" />
      </div>

      <div v-if="exporting">
        <ProgressBar :value="progress" :show-value="true" />
        <p class="mt-1 text-center text-xs text-surface-500">エクスポート中...</p>
      </div>

      <div v-if="downloadUrl" class="rounded-lg border border-green-200 bg-green-50 p-4 text-center dark:border-green-800 dark:bg-green-950">
        <i class="pi pi-check-circle mb-2 text-2xl text-green-500" />
        <p class="mb-2 text-sm">エクスポートが完了しました</p>
        <Button label="ダウンロード" icon="pi pi-download" @click="handleDownload" />
      </div>
    </div>

    <template #footer>
      <Button label="閉じる" severity="secondary" @click="close" />
      <Button
        v-if="!downloadUrl"
        label="エクスポート開始"
        icon="pi pi-download"
        :loading="exporting"
        :disabled="selectedTargets.length === 0"
        @click="startExport"
      />
    </template>
  </Dialog>
</template>
