<script setup lang="ts">
import type {
  EmailOutboxSummary,
  EmailOutboxMetrics,
  EmailOutboxStatus,
  PageMeta,
} from '~/composables/useEmailOutboxAdminApi'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { fetchList, fetchMetrics, retryDeadLetter, cancelPending } = useEmailOutboxAdminApi()
const { error: showError, success: showSuccess } = useNotification()
const { formatDateTime } = useDatetime()
const router = useRouter()

// ===== フィルタ状態 =====

interface FilterState {
  status: EmailOutboxStatus | undefined
  sourceDomain: string
  fromDate: string
  toDate: string
}

const filters = ref<FilterState>({
  status: undefined,
  sourceDomain: '',
  fromDate: '',
  toDate: '',
})

const statusOptions = computed(() => [
  { label: t('email_outbox.filter.all_statuses'), value: undefined },
  { label: t('email_outbox.status.PENDING'), value: 'PENDING' },
  { label: t('email_outbox.status.SENDING'), value: 'SENDING' },
  { label: t('email_outbox.status.SENT'), value: 'SENT' },
  { label: t('email_outbox.status.FAILED'), value: 'FAILED' },
  { label: t('email_outbox.status.DEAD_LETTER'), value: 'DEAD_LETTER' },
  { label: t('email_outbox.status.CANCELLED'), value: 'CANCELLED' },
])

// ===== 一覧状態 =====

const items = ref<EmailOutboxSummary[]>([])
const meta = ref<PageMeta | null>(null)
const loading = ref(false)
const currentPage = ref(0)
const pageSize = ref(20)

// ===== メトリクス状態 =====

const metrics = ref<EmailOutboxMetrics | null>(null)
const metricsLoading = ref(false)

// ===== 操作状態 =====

const actionLoading = ref<string | null>(null)

// ===== データ取得 =====

async function load() {
  loading.value = true
  try {
    const res = await fetchList({
      status: filters.value.status,
      sourceDomain: filters.value.sourceDomain || undefined,
      fromDate: filters.value.fromDate || undefined,
      toDate: filters.value.toDate || undefined,
      page: currentPage.value,
      size: pageSize.value,
    })
    items.value = res.data
    meta.value = res.meta
  } catch (e) {
    console.error(e)
    showError(t('email_outbox.load_failed'))
  } finally {
    loading.value = false
  }
}

async function loadMetrics() {
  metricsLoading.value = true
  try {
    const res = await fetchMetrics()
    metrics.value = res.data
  } catch (e) {
    console.error(e)
    // メトリクスは補助情報のためエラートーストなしで続行
  } finally {
    metricsLoading.value = false
  }
}

// ===== フィルタ操作 =====

function applyFilters() {
  currentPage.value = 0
  void load()
}

function clearFilters() {
  filters.value = {
    status: undefined,
    sourceDomain: '',
    fromDate: '',
    toDate: '',
  }
  currentPage.value = 0
  void load()
}

// ===== ページネーション =====

function prevPage() {
  if (currentPage.value > 0) {
    currentPage.value--
    void load()
  }
}

function nextPage() {
  if (meta.value && currentPage.value < meta.value.totalPages - 1) {
    currentPage.value++
    void load()
  }
}

// ===== アクション =====

async function onRetry(item: EmailOutboxSummary) {
  actionLoading.value = item.id
  try {
    await retryDeadLetter(item.id)
    showSuccess(t('email_outbox.retry_success'))
    void load()
  } catch (err: unknown) {
    const status = (err as { statusCode?: number })?.statusCode
    if (status === 409) {
      showError(t('email_outbox.retry_conflict'))
    } else {
      showError(t('email_outbox.load_failed'))
    }
  } finally {
    actionLoading.value = null
  }
}

async function onCancel(item: EmailOutboxSummary) {
  actionLoading.value = item.id
  try {
    await cancelPending(item.id)
    showSuccess(t('email_outbox.cancel_success'))
    void load()
  } catch (err: unknown) {
    const status = (err as { statusCode?: number })?.statusCode
    if (status === 409) {
      showError(t('email_outbox.cancel_conflict'))
    } else {
      showError(t('email_outbox.load_failed'))
    }
  } finally {
    actionLoading.value = null
  }
}

function openDetail(id: string) {
  void router.push(`/system-admin/email-outbox/${id}`)
}

// ===== 表示ヘルパー =====

function shortId(id: string): string {
  return id.slice(0, 8)
}

function formatSuccessRate(rate: number | null): string {
  if (rate === null) return t('email_outbox.na')
  return `${(rate * 100).toFixed(1)}%`
}

onMounted(() => {
  void load()
  void loadMetrics()
})
</script>

<template>
  <div class="container mx-auto max-w-7xl space-y-4 p-4">
    <!-- ヘッダー -->
    <header class="flex items-center justify-between">
      <h1 class="flex items-center gap-2 text-xl font-bold">
        <i class="pi pi-envelope" aria-hidden="true" />
        {{ t('email_outbox.title') }}
      </h1>
    </header>

    <!-- メトリクス KPI カード -->
    <div class="grid grid-cols-2 gap-3 md:grid-cols-4">
      <div
        class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
      >
        <div class="text-sm text-surface-500">{{ t('email_outbox.metrics.pending') }}</div>
        <div class="mt-1 text-2xl font-bold text-blue-600 dark:text-blue-400">
          {{ metricsLoading ? '...' : (metrics?.queueDepthPending ?? t('email_outbox.na')) }}
        </div>
      </div>
      <div
        class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
      >
        <div class="text-sm text-surface-500">{{ t('email_outbox.metrics.dead_letter') }}</div>
        <div class="mt-1 text-2xl font-bold text-red-600 dark:text-red-400">
          {{ metricsLoading ? '...' : (metrics?.queueDepthDeadLetter ?? t('email_outbox.na')) }}
        </div>
      </div>
      <div
        class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
      >
        <div class="text-sm text-surface-500">{{ t('email_outbox.metrics.success_rate') }}</div>
        <div class="mt-1 text-2xl font-bold text-green-600 dark:text-green-400">
          {{ metricsLoading ? '...' : formatSuccessRate(metrics?.successRate24h ?? null) }}
        </div>
      </div>
      <div
        class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
      >
        <div class="text-sm text-surface-500">{{ t('email_outbox.metrics.oldest_pending') }}</div>
        <div class="mt-1 text-2xl font-bold text-orange-600 dark:text-orange-400">
          {{
            metricsLoading
              ? '...'
              : (metrics?.oldestPendingAgeSeconds !== null && metrics?.oldestPendingAgeSeconds !== undefined
                  ? metrics.oldestPendingAgeSeconds
                  : t('email_outbox.na'))
          }}
        </div>
      </div>
    </div>

    <!-- フィルタバー -->
    <div
      class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
    >
      <div class="flex flex-wrap items-end gap-3">
        <!-- ステータスフィルタ -->
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ t('email_outbox.filter.status') }}</label>
          <Select
            v-model="filters.status"
            :options="statusOptions"
            option-label="label"
            option-value="value"
            class="w-48"
            :placeholder="t('email_outbox.filter.all_statuses')"
          />
        </div>
        <!-- 送信元ドメイン -->
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ t('email_outbox.filter.source_domain') }}</label>
          <InputText
            v-model="filters.sourceDomain"
            class="w-40"
            :placeholder="t('email_outbox.filter.source_domain')"
          />
        </div>
        <!-- 開始日 -->
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ t('email_outbox.filter.from_date') }}</label>
          <InputText v-model="filters.fromDate" type="date" class="w-40" />
        </div>
        <!-- 終了日 -->
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ t('email_outbox.filter.to_date') }}</label>
          <InputText v-model="filters.toDate" type="date" class="w-40" />
        </div>
        <!-- ボタン -->
        <div class="flex gap-2">
          <Button
            :label="t('email_outbox.filter.apply')"
            icon="pi pi-search"
            size="small"
            @click="applyFilters"
          />
          <Button
            :label="t('email_outbox.filter.clear')"
            icon="pi pi-times"
            size="small"
            severity="secondary"
            @click="clearFilters"
          />
        </div>
      </div>
    </div>

    <!-- 一覧テーブル -->
    <div
      class="rounded-xl border border-surface-300 bg-surface-0 dark:border-surface-600 dark:bg-surface-800"
    >
      <div v-if="loading" class="py-12 text-center text-sm text-surface-500">
        <i class="pi pi-spin pi-spinner mr-2" aria-hidden="true" />
        {{ t('email_outbox.load_failed') }}
      </div>
      <div v-else-if="items.length === 0" class="py-12 text-center text-sm text-surface-500">
        {{ t('email_outbox.no_data') }}
      </div>
      <template v-else>
        <div class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead class="border-b border-surface-300 dark:border-surface-600">
              <tr class="text-left text-xs font-semibold uppercase text-surface-500">
                <th class="px-4 py-3">{{ t('email_outbox.table.id') }}</th>
                <th class="px-4 py-3">{{ t('email_outbox.table.status') }}</th>
                <th class="px-4 py-3">{{ t('email_outbox.table.template') }}</th>
                <th class="px-4 py-3">{{ t('email_outbox.table.source') }}</th>
                <th class="px-4 py-3">{{ t('email_outbox.table.retry_count') }}</th>
                <th class="px-4 py-3">{{ t('email_outbox.table.created_at') }}</th>
                <th class="px-4 py-3">{{ t('email_outbox.table.actions') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in items"
                :key="item.id"
                class="cursor-pointer border-b border-surface-200 transition-colors hover:bg-surface-50 dark:border-surface-700 dark:hover:bg-surface-700"
                @click="openDetail(item.id)"
              >
                <td class="px-4 py-3 font-mono text-xs">{{ shortId(item.id) }}</td>
                <td class="px-4 py-3">
                  <span
                    :class="{
                      'rounded-full px-2 py-0.5 text-xs font-semibold': true,
                      'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300':
                        item.status === 'PENDING',
                      'bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-300':
                        item.status === 'SENDING',
                      'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300':
                        item.status === 'SENT',
                      'bg-orange-100 text-orange-700 dark:bg-orange-900 dark:text-orange-300':
                        item.status === 'FAILED',
                      'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300':
                        item.status === 'DEAD_LETTER',
                      'bg-surface-100 text-surface-600 dark:bg-surface-700 dark:text-surface-400':
                        item.status === 'CANCELLED',
                    }"
                  >
                    {{ t(`email_outbox.status.${item.status}`) }}
                  </span>
                </td>
                <td class="px-4 py-3">{{ item.templateKind }}</td>
                <td class="px-4 py-3">{{ item.sourceDomain }}</td>
                <td class="px-4 py-3 text-right">{{ item.retryCount }}</td>
                <td class="px-4 py-3 text-xs">{{ formatDateTime(item.createdAt) }}</td>
                <td class="px-4 py-3" @click.stop>
                  <div class="flex gap-2">
                    <Button
                      v-if="item.status === 'DEAD_LETTER'"
                      :label="t('email_outbox.action.retry')"
                      icon="pi pi-refresh"
                      size="small"
                      severity="warning"
                      :loading="actionLoading === item.id"
                      @click="onRetry(item)"
                    />
                    <Button
                      v-if="item.status === 'PENDING'"
                      :label="t('email_outbox.action.cancel')"
                      icon="pi pi-times"
                      size="small"
                      severity="danger"
                      :loading="actionLoading === item.id"
                      @click="onCancel(item)"
                    />
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- ページネーション -->
        <div
          v-if="meta"
          class="flex items-center justify-between px-4 py-3 text-sm text-surface-600"
        >
          <span>
            {{ meta.totalElements }} 件中
            {{ meta.page * meta.size + 1 }}〜{{ Math.min((meta.page + 1) * meta.size, meta.totalElements) }} 件
          </span>
          <div class="flex gap-2">
            <Button
              :label="t('email_outbox.action.back')"
              icon="pi pi-chevron-left"
              size="small"
              severity="secondary"
              :disabled="currentPage === 0"
              @click="prevPage"
            />
            <Button
              icon="pi pi-chevron-right"
              size="small"
              severity="secondary"
              :disabled="currentPage >= meta.totalPages - 1"
              @click="nextPage"
            />
          </div>
        </div>
      </template>
    </div>
  </div>
</template>
