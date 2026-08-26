<script setup lang="ts">
import type {
  BatchEndpointSummary,
  BatchJobLogResponse,
  BatchEndpointLastStatus,
  BatchTriggerStatus,
} from '~/types/system-admin'

/**
 * F10.X 第三陣（丁組） — システム管理者向けバッチ管理ページ。
 *
 * <p>登録済みバッチの一覧表示・名前検索・ステータス絞り込み・実行/同期実行ボタンと、
 * バッチ詳細（直近 1 件の {@code batch_job_logs}）をモーダルで確認できる。</p>
 *
 * <p>非同期実行は即座にトーストで通知し、結果は通知タブ / バックエンドの完了イベント経由で受動的に拾える設計。</p>
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const notification = useNotification()
const batchApi = useSystemAdminBatchApi()
const { formatDateTime } = useDatetime()

const batches = ref<BatchEndpointSummary[]>([])
const loading = ref(false)
const triggeringName = ref<string | null>(null)

const searchKeyword = ref('')
const statusFilter = ref<BatchEndpointLastStatus | 'ALL'>('ALL')

const detailDialogOpen = ref(false)
const detailLoading = ref(false)
const detailTarget = ref<BatchEndpointSummary | null>(null)
const detailLog = ref<BatchJobLogResponse | null>(null)

interface StatusOption {
  label: string
  value: BatchEndpointLastStatus | 'ALL'
}

const statusOptions = computed<StatusOption[]>(() => [
  { label: t('systemAdmin.batches.filter.statusAll'), value: 'ALL' },
  { label: t('systemAdmin.batches.status.success'), value: 'SUCCESS' },
  { label: t('systemAdmin.batches.status.failed'), value: 'FAILED' },
  { label: t('systemAdmin.batches.status.running'), value: 'RUNNING' },
  { label: t('systemAdmin.batches.status.skipped'), value: 'SKIPPED' },
  { label: t('systemAdmin.batches.status.resumed'), value: 'RESUMED' },
])

const filteredBatches = computed(() => {
  const keyword = searchKeyword.value.trim().toLowerCase()
  return batches.value.filter((b) => {
    if (keyword.length > 0) {
      const hay = `${b.name} ${b.description}`.toLowerCase()
      if (!hay.includes(keyword)) return false
    }
    if (statusFilter.value !== 'ALL') {
      if (b.lastStatus !== statusFilter.value) return false
    }
    return true
  })
})

async function load() {
  loading.value = true
  try {
    const res = await batchApi.listBatches()
    batches.value = res.data
  } catch (e) {
    console.error('batches.vue: failed to load batches', e)
    notification.error(t('systemAdmin.batches.toast.loadFailed'))
    batches.value = []
  } finally {
    loading.value = false
  }
}

async function runBatch(batch: BatchEndpointSummary, sync: boolean) {
  if (triggeringName.value !== null) return
  triggeringName.value = batch.name
  try {
    const result = await batchApi.trigger(batch.name, { sync })
    handleTriggerResult(batch, result.httpStatus, result.data.status, result.data.message)
    // 起動後にステータスを更新
    await load()
  } catch (e) {
    console.error('batches.vue: trigger failed', e)
    notification.error(t('systemAdmin.batches.toast.runFailed', { name: batch.name }))
  } finally {
    triggeringName.value = null
  }
}

function handleTriggerResult(
  batch: BatchEndpointSummary,
  httpStatus: number,
  status: BatchTriggerStatus,
  message: string,
) {
  if (status === 'LOCKED' || httpStatus === 409) {
    notification.warn(t('systemAdmin.batches.toast.locked', { name: batch.name }), message)
    return
  }
  if (status === 'ACCEPTED') {
    notification.info(
      t('systemAdmin.batches.toast.runStarted', { name: batch.name }),
      t('systemAdmin.batches.toast.runStartedDetail'),
    )
    return
  }
  if (status === 'COMPLETED') {
    notification.success(t('systemAdmin.batches.toast.runCompleted', { name: batch.name }), message)
    return
  }
  // FAILED
  notification.error(t('systemAdmin.batches.toast.runFailed', { name: batch.name }), message)
}

async function openDetail(batch: BatchEndpointSummary) {
  detailTarget.value = batch
  detailLog.value = null
  detailDialogOpen.value = true
  detailLoading.value = true
  try {
    const res = await batchApi.getStatus(batch.name)
    detailLog.value = res.data.lastJobLog
  } catch (e) {
    console.error('batches.vue: getStatus failed', e)
    notification.error(t('systemAdmin.batches.toast.statusFailed'))
  } finally {
    detailLoading.value = false
  }
}

function closeDetail() {
  detailDialogOpen.value = false
  detailTarget.value = null
  detailLog.value = null
}

function statusLabel(status: string | null): string {
  if (!status) return t('systemAdmin.batches.status.unknown')
  const key = `systemAdmin.batches.status.${status.toLowerCase()}`
  // 翻訳キーが存在しない場合は原文を返す
  const translated = t(key)
  return translated === key ? status : translated
}

function statusSeverity(status: string | null): 'success' | 'danger' | 'info' | 'secondary' | 'warn' {
  switch (status) {
    case 'SUCCESS':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'RUNNING':
      return 'info'
    case 'SKIPPED':
      return 'warn'
    case 'RESUMED':
      // 実行そのものではなく「停止から復帰した」境界の目印なので、成功とは色を分ける。
      return 'info'
    default:
      return 'secondary'
  }
}


onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-screen-xl space-y-6 p-4">
    <header class="flex items-center justify-between">
      <div>
        <span
          class="rounded-full bg-red-100 px-2.5 py-0.5 text-xs font-semibold text-red-600 dark:bg-red-900/30 dark:text-red-400"
        >
          SYSTEM ADMIN
        </span>
        <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
          {{ t('systemAdmin.batches.title') }}
        </h1>
        <p class="mt-0.5 text-sm text-surface-500">
          {{ t('systemAdmin.batches.description') }}
        </p>
      </div>
      <Button
        v-tooltip.left="t('systemAdmin.batches.button.refresh')"
        icon="pi pi-refresh"
        text
        rounded
        :loading="loading"
        @click="load"
      />
    </header>

    <Card>
      <template #content>
        <div class="flex flex-col gap-4 sm:flex-row sm:items-end">
          <div class="flex flex-1 flex-col gap-1.5">
            <label class="text-sm font-medium text-surface-600 dark:text-surface-300">
              {{ t('systemAdmin.batches.filter.search') }}
            </label>
            <InputText v-model="searchKeyword" :placeholder="t('systemAdmin.batches.filter.search')" />
          </div>
          <div class="flex flex-col gap-1.5">
            <label class="text-sm font-medium text-surface-600 dark:text-surface-300">
              {{ t('systemAdmin.batches.filter.status') }}
            </label>
            <Select
              v-model="statusFilter"
              :options="statusOptions"
              option-label="label"
              option-value="value"
              class="w-48"
            />
          </div>
        </div>
      </template>
    </Card>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <i class="pi pi-spin pi-spinner mr-2 text-2xl text-surface-400" aria-hidden="true" />
    </div>

    <template v-else-if="filteredBatches.length > 0">
      <DataTable :value="filteredBatches" striped-rows class="text-sm" data-test="batch-table">
        <Column field="name" :header="t('systemAdmin.batches.column.name')" style="min-width: 14rem">
          <template #body="{ data: row }: { data: BatchEndpointSummary }">
            <div class="font-medium text-surface-700 dark:text-surface-200">{{ row.name }}</div>
            <div v-if="row.schedulerLockName" class="text-xs text-surface-400">
              lock: {{ row.schedulerLockName }}
            </div>
          </template>
        </Column>
        <Column
          field="description"
          :header="t('systemAdmin.batches.column.description')"
          style="min-width: 18rem"
        />
        <Column
          field="lastStatus"
          :header="t('systemAdmin.batches.column.lastStatus')"
          style="width: 9rem"
        >
          <template #body="{ data: row }: { data: BatchEndpointSummary }">
            <Tag :value="statusLabel(row.lastStatus)" :severity="statusSeverity(row.lastStatus)" />
          </template>
        </Column>
        <Column
          field="lastStartedAt"
          :header="t('systemAdmin.batches.column.lastRun')"
          style="width: 12rem"
        >
          <template #body="{ data: row }: { data: BatchEndpointSummary }">
            {{ formatDateTime(row.lastStartedAt) }}
          </template>
        </Column>
        <Column :header="t('systemAdmin.batches.column.actions')" style="width: 18rem">
          <template #body="{ data: row }: { data: BatchEndpointSummary }">
            <div class="flex items-center gap-1">
              <Button
                :label="t('systemAdmin.batches.button.run')"
                icon="pi pi-play"
                size="small"
                :loading="triggeringName === row.name"
                :disabled="triggeringName !== null && triggeringName !== row.name"
                :data-test="`run-${row.name}`"
                @click="runBatch(row, false)"
              />
              <Button
                :label="t('systemAdmin.batches.button.runSync')"
                icon="pi pi-bolt"
                size="small"
                severity="secondary"
                :loading="triggeringName === row.name"
                :disabled="triggeringName !== null && triggeringName !== row.name"
                :data-test="`run-sync-${row.name}`"
                @click="runBatch(row, true)"
              />
              <Button
                :label="t('systemAdmin.batches.button.viewDetail')"
                icon="pi pi-info-circle"
                size="small"
                text
                :data-test="`detail-${row.name}`"
                @click="openDetail(row)"
              />
            </div>
          </template>
        </Column>
      </DataTable>
    </template>

    <div
      v-else-if="batches.length === 0"
      class="flex flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-surface-300 py-16 text-surface-400 dark:border-surface-600"
    >
      <i class="pi pi-inbox text-4xl" aria-hidden="true" />
      <p class="text-sm">{{ t('systemAdmin.batches.empty') }}</p>
    </div>

    <div
      v-else
      class="flex flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-surface-300 py-16 text-surface-400 dark:border-surface-600"
    >
      <i class="pi pi-search text-4xl" aria-hidden="true" />
      <p class="text-sm">{{ t('systemAdmin.batches.noResults') }}</p>
    </div>

    <Dialog
      v-model:visible="detailDialogOpen"
      modal
      :header="t('systemAdmin.batches.detail.title')"
      :style="{ width: '36rem' }"
      :draggable="false"
      @hide="closeDetail"
    >
      <div v-if="detailLoading" class="flex items-center justify-center py-8">
        <i class="pi pi-spin pi-spinner text-2xl text-surface-400" aria-hidden="true" />
      </div>
      <template v-else>
        <div v-if="detailTarget" class="mb-3">
          <p class="text-sm font-medium text-surface-700 dark:text-surface-200">
            {{ detailTarget.name }}
          </p>
          <p class="mt-0.5 text-xs text-surface-400">{{ detailTarget.description }}</p>
        </div>
        <div v-if="!detailLog" class="rounded border border-dashed border-surface-300 px-4 py-6 text-center text-sm text-surface-400 dark:border-surface-600">
          {{ t('systemAdmin.batches.detail.noHistory') }}
        </div>
        <dl v-else class="grid grid-cols-2 gap-3 text-sm">
          <dt class="text-surface-500">{{ t('systemAdmin.batches.detail.jobName') }}</dt>
          <dd class="text-surface-800 dark:text-surface-200">{{ detailLog.jobName }}</dd>
          <dt class="text-surface-500">{{ t('systemAdmin.batches.detail.status') }}</dt>
          <dd>
            <Tag :value="statusLabel(detailLog.status)" :severity="statusSeverity(detailLog.status)" />
          </dd>
          <dt class="text-surface-500">{{ t('systemAdmin.batches.detail.startedAt') }}</dt>
          <dd class="text-surface-800 dark:text-surface-200">
            {{ formatDateTime(detailLog.startedAt) }}
          </dd>
          <dt class="text-surface-500">{{ t('systemAdmin.batches.detail.finishedAt') }}</dt>
          <dd class="text-surface-800 dark:text-surface-200">
            {{ formatDateTime(detailLog.finishedAt) }}
          </dd>
          <dt class="text-surface-500">{{ t('systemAdmin.batches.detail.processedCount') }}</dt>
          <dd class="text-surface-800 dark:text-surface-200">{{ detailLog.processedCount }}</dd>
          <template v-if="detailLog.errorMessage">
            <dt class="text-surface-500">{{ t('systemAdmin.batches.detail.errorMessage') }}</dt>
            <dd class="whitespace-pre-wrap break-all text-red-600 dark:text-red-400">
              {{ detailLog.errorMessage }}
            </dd>
          </template>
        </dl>
      </template>
      <template #footer>
        <Button :label="t('systemAdmin.batches.button.close')" text @click="closeDetail" />
      </template>
    </Dialog>
  </div>
</template>
