<script setup lang="ts">
import type { IncidentSummaryResponse, IncidentStatus } from '~/types/incident'

const props = defineProps<{
  scopeType: string
  scopeId: number
  canManage?: boolean
}>()

const emit = defineEmits<{
  select: [incident: IncidentSummaryResponse]
  create: []
  manageCategories: []
}>()

const { listIncidents } = useIncidentApi()
const { error: showError } = useNotification()
const { relativeTime } = useRelativeTime()

const items = ref<IncidentSummaryResponse[]>([])
const loading = ref(false)
const filterStatus = ref<IncidentStatus | ''>('')
const page = ref(0)
const size = ref(20)
const totalElements = ref(0)
const totalPages = ref(0)

const statusOptions = [
  { label: 'すべて', value: '' },
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

async function loadItems() {
  loading.value = true
  try {
    const res = await listIncidents(props.scopeType, props.scopeId, {
      status: filterStatus.value || undefined,
      page: page.value,
      size: size.value,
    })
    items.value = res.data
    totalElements.value = res.meta.totalElements
    totalPages.value = res.meta.totalPages
  } catch {
    showError('インシデント一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function onFilterChange() {
  page.value = 0
  loadItems()
}

function onPageChange(newPage: number) {
  page.value = newPage
  loadItems()
}

function isSlaBreached(item: IncidentSummaryResponse): boolean {
  return item.isSlaBreached
}

function isSlaApproaching(item: IncidentSummaryResponse): boolean {
  if (!item.slaDeadline || item.isSlaBreached) return false
  const deadline = new Date(item.slaDeadline)
  const now = new Date()
  const hoursLeft = (deadline.getTime() - now.getTime()) / (1000 * 60 * 60)
  return hoursLeft > 0 && hoursLeft <= 2
}

onMounted(() => loadItems())
defineExpose({ refresh: loadItems })
</script>

<template>
  <div>
    <!-- ヘッダー -->
    <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
      <h2 class="text-lg font-semibold">インシデント管理</h2>
      <div class="flex items-center gap-2">
        <Button
          v-if="canManage"
          label="カテゴリ管理"
          icon="pi pi-cog"
          text
          size="small"
          @click="emit('manageCategories')"
        />
        <Button
          v-if="canManage"
          label="報告"
          icon="pi pi-plus"
          size="small"
          @click="emit('create')"
        />
      </div>
    </div>

    <!-- フィルタ -->
    <div class="mb-4">
      <Select
        v-model="filterStatus"
        :options="statusOptions"
        option-label="label"
        option-value="value"
        placeholder="ステータスで絞り込み"
        class="w-48"
        @change="onFilterChange"
      />
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 40px; height: 40px" />
    </div>

    <!-- リスト -->
    <div v-else class="flex flex-col gap-3">
      <button
        v-for="item in items"
        :key="item.id"
        class="flex items-start gap-3 rounded-xl border border-surface-300 bg-surface-0 p-4 text-left transition-shadow hover:shadow-sm dark:border-surface-600 dark:bg-surface-800"
        @click="emit('select', item)"
      >
        <div class="min-w-0 flex-1">
          <div class="mb-1 flex flex-wrap items-center gap-2">
            <span
              :class="getStatusClass(item.status)"
              class="rounded px-1.5 py-0.5 text-xs font-medium"
            >
              {{ getStatusLabel(item.status) }}
            </span>
            <span
              :class="getPriorityClass(item.priority)"
              class="rounded px-1.5 py-0.5 text-xs font-medium"
            >
              {{ getPriorityLabel(item.priority) }}
            </span>
            <!-- SLA 表示 -->
            <span
              v-if="isSlaBreached(item)"
              class="rounded bg-red-100 px-1.5 py-0.5 text-xs font-medium text-red-700 dark:bg-red-900 dark:text-red-300"
            >
              <i class="pi pi-exclamation-triangle mr-1" />SLA超過
            </span>
            <span
              v-else-if="isSlaApproaching(item)"
              class="rounded bg-orange-100 px-1.5 py-0.5 text-xs font-medium text-orange-700 dark:bg-orange-900 dark:text-orange-300"
            >
              <i class="pi pi-clock mr-1" />SLA間近
            </span>
          </div>
          <h3 class="text-sm font-semibold">{{ item.title }}</h3>
          <div class="mt-1 text-xs text-surface-400">
            <span>{{ relativeTime(item.createdAt) }}</span>
            <span v-if="item.slaDeadline"> / 期限: {{ new Date(item.slaDeadline).toLocaleString('ja-JP') }}</span>
          </div>
        </div>
        <i class="pi pi-chevron-right mt-1 text-surface-300" />
      </button>
    </div>

    <!-- 空表示 -->
    <div v-if="!loading && items.length === 0" class="py-12 text-center">
      <i class="pi pi-shield mb-3 text-4xl text-surface-300" />
      <p class="text-surface-400">インシデントがありません</p>
    </div>

    <!-- ページネーション -->
    <div v-if="totalPages > 1" class="mt-4 flex items-center justify-center gap-2">
      <Button
        icon="pi pi-chevron-left"
        text
        size="small"
        :disabled="page === 0"
        @click="onPageChange(page - 1)"
      />
      <span class="text-sm text-surface-500">
        {{ page + 1 }} / {{ totalPages }} ({{ totalElements }}件)
      </span>
      <Button
        icon="pi pi-chevron-right"
        text
        size="small"
        :disabled="page >= totalPages - 1"
        @click="onPageChange(page + 1)"
      />
    </div>
  </div>
</template>
