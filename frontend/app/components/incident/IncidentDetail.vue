<script setup lang="ts">
import type {
  IncidentResponse,
  IncidentStatus,
  IncidentCommentResponse,
} from '~/types/incident'

const props = defineProps<{
  incidentId: number
  canManage?: boolean
}>()

const emit = defineEmits<{
  back: []
  updated: []
}>()

const {
  getIncident,
  changeStatus,
  assignIncident,
  deleteIncident,
  getComments,
} = useIncidentApi()
const { success: showSuccess, error: showError } = useNotification()
const { relativeTime } = useRelativeTime()

const incident = ref<IncidentResponse | null>(null)
const comments = ref<IncidentCommentResponse[]>([])
const loading = ref(false)
const showStatusDialog = ref(false)
const showAssignDialog = ref(false)
const selectedStatus = ref<IncidentStatus>('OPEN')
const statusComment = ref('')
const assigneeId = ref<number | undefined>(undefined)
const submitting = ref(false)

const statusOptions = [
  { label: 'オープン', value: 'OPEN' },
  { label: '対応中', value: 'IN_PROGRESS' },
  { label: '解決済み', value: 'RESOLVED' },
  { label: 'クローズ', value: 'CLOSED' },
]

function getStatusClass(status: IncidentStatus): string {
  switch (status) {
    case 'OPEN': return 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300'
    case 'IN_PROGRESS': return 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-300'
    case 'RESOLVED': return 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300'
    case 'CLOSED': return 'bg-surface-100 text-surface-500 dark:bg-surface-700 dark:text-surface-400'
    default: return 'bg-surface-100'
  }
}

function getStatusLabel(status: IncidentStatus): string {
  const labels: Record<IncidentStatus, string> = {
    OPEN: 'オープン',
    IN_PROGRESS: '対応中',
    RESOLVED: '解決済み',
    CLOSED: 'クローズ',
  }
  return labels[status] || status
}

function getPriorityClass(priority: string): string {
  switch (priority) {
    case 'LOW': return 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300'
    case 'MEDIUM': return 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-300'
    case 'HIGH': return 'bg-orange-100 text-orange-700 dark:bg-orange-900 dark:text-orange-300'
    case 'CRITICAL': return 'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300'
    default: return 'bg-surface-100'
  }
}

function getPriorityLabel(priority: string): string {
  const labels: Record<string, string> = { LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '緊急' }
  return labels[priority] || priority
}

async function loadIncident() {
  loading.value = true
  try {
    const [incRes, comRes] = await Promise.all([
      getIncident(props.incidentId),
      getComments(props.incidentId),
    ])
    incident.value = incRes.data
    comments.value = comRes.data
  } catch {
    showError('インシデントの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openStatusDialog() {
  if (!incident.value) return
  selectedStatus.value = incident.value.status
  statusComment.value = ''
  showStatusDialog.value = true
}

async function onChangeStatus() {
  if (submitting.value) return
  submitting.value = true
  try {
    const res = await changeStatus(props.incidentId, {
      status: selectedStatus.value,
      comment: statusComment.value || undefined,
    })
    incident.value = res.data
    showStatusDialog.value = false
    showSuccess('ステータスを変更しました')
    emit('updated')
  } catch {
    showError('ステータスの変更に失敗しました')
  } finally {
    submitting.value = false
  }
}

async function onAssign() {
  if (!assigneeId.value || submitting.value) return
  submitting.value = true
  try {
    const res = await assignIncident(props.incidentId, {
      assigneeId: assigneeId.value,
      assigneeType: 'USER',
    })
    incident.value = res.data
    showAssignDialog.value = false
    assigneeId.value = undefined
    showSuccess('担当者を割り当てました')
    emit('updated')
  } catch {
    showError('担当者の割り当てに失敗しました')
  } finally {
    submitting.value = false
  }
}

async function onDelete() {
  try {
    await deleteIncident(props.incidentId)
    showSuccess('インシデントを削除しました')
    emit('back')
  } catch {
    showError('削除に失敗しました')
  }
}

onMounted(() => loadIncident())
watch(() => props.incidentId, () => loadIncident())
</script>

<template>
  <div v-if="incident">
    <!-- 戻るボタン + アクション -->
    <div class="mb-4 flex items-center justify-between">
      <Button icon="pi pi-arrow-left" label="一覧へ戻る" text size="small" @click="emit('back')" />
      <div v-if="canManage" class="flex items-center gap-1">
        <Button
          icon="pi pi-sync"
          label="ステータス変更"
          text
          size="small"
          @click="openStatusDialog"
        />
        <Button
          icon="pi pi-user"
          label="担当者割当"
          text
          size="small"
          @click="showAssignDialog = true"
        />
        <Button
          icon="pi pi-trash"
          label="削除"
          text
          size="small"
          severity="danger"
          @click="onDelete"
        />
      </div>
    </div>

    <!-- インシデント本体 -->
    <div class="rounded-xl border border-surface-300 bg-surface-0 p-6 dark:border-surface-600 dark:bg-surface-800">
      <div class="mb-3 flex flex-wrap items-center gap-2">
        <span
          :class="getStatusClass(incident.status)"
          class="rounded px-2 py-0.5 text-xs font-medium"
        >
          {{ getStatusLabel(incident.status) }}
        </span>
        <span
          :class="getPriorityClass(incident.priority)"
          class="rounded px-2 py-0.5 text-xs font-medium"
        >
          {{ getPriorityLabel(incident.priority) }}
        </span>
        <span
          v-if="incident.isSlaBreached"
          class="rounded bg-red-100 px-2 py-0.5 text-xs font-medium text-red-700 dark:bg-red-900 dark:text-red-300"
        >
          <i class="pi pi-exclamation-triangle mr-1" />SLA超過
        </span>
      </div>

      <h2 class="mb-2 text-xl font-bold">{{ incident.title }}</h2>

      <div class="mb-4 flex flex-wrap items-center gap-3 text-sm text-surface-400">
        <span>報告日: {{ relativeTime(incident.createdAt) }}</span>
        <span>更新日: {{ relativeTime(incident.updatedAt) }}</span>
        <span v-if="incident.slaDeadline">
          期限: {{ new Date(incident.slaDeadline).toLocaleString('ja-JP') }}
        </span>
      </div>

      <div v-if="incident.description" class="whitespace-pre-wrap text-sm leading-relaxed">
        {{ incident.description }}
      </div>
      <div v-else class="text-sm text-surface-400">説明なし</div>
    </div>

    <!-- コメント一覧 -->
    <div class="mt-6">
      <h3 class="mb-3 text-sm font-semibold text-surface-500">
        コメント {{ comments.length }}件
      </h3>
      <div class="flex flex-col gap-3">
        <div
          v-for="comment in comments"
          :key="comment.id"
          class="rounded-lg border border-surface-100 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
        >
          <div class="mb-2 flex items-center gap-2 text-xs text-surface-400">
            <span class="font-medium text-surface-600 dark:text-surface-300">
              ユーザー #{{ comment.userId }}
            </span>
            <span>{{ relativeTime(comment.createdAt) }}</span>
          </div>
          <p class="whitespace-pre-wrap text-sm">{{ comment.body }}</p>
        </div>
      </div>
      <div v-if="comments.length === 0" class="py-4 text-center text-sm text-surface-400">
        コメントはありません
      </div>
    </div>

    <!-- ステータス変更ダイアログ -->
    <Dialog
      v-model:visible="showStatusDialog"
      header="ステータス変更"
      modal
      class="w-full max-w-md"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">新しいステータス</label>
          <Select
            v-model="selectedStatus"
            :options="statusOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">コメント（任意）</label>
          <Textarea
            v-model="statusComment"
            auto-resize
            rows="3"
            class="w-full"
            placeholder="ステータス変更の理由..."
          />
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showStatusDialog = false" />
        <Button label="変更" :loading="submitting" @click="onChangeStatus" />
      </template>
    </Dialog>

    <!-- 担当者割当ダイアログ -->
    <Dialog
      v-model:visible="showAssignDialog"
      header="担当者割当"
      modal
      class="w-full max-w-md"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">ユーザーID</label>
          <InputNumber v-model="assigneeId" class="w-full" placeholder="担当者のユーザーID" />
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showAssignDialog = false" />
        <Button label="割当" :loading="submitting" :disabled="!assigneeId" @click="onAssign" />
      </template>
    </Dialog>
  </div>

  <div v-else-if="loading" class="flex justify-center py-12">
    <ProgressSpinner style="width: 40px; height: 40px" />
  </div>
</template>
