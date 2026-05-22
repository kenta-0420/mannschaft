<script setup lang="ts">
import type {
  GdprPurgeStatusRow,
  GdprPurgeSummaryData,
  GdprPurgeStatusPage,
  GdprPurgeStatusQuery,
} from '~/types/system-admin'

/**
 * Phase E/F — GDPR パージ状況 管理画面。
 *
 * <p>システム管理者が GDPR パージバッチの実行状況をドメイン別・ユーザー別に確認できる
 * 管理ダッシュボード。CSV エクスポート機能と PENDING 行の手動 retry 機能つき。</p>
 *
 * <p>アラート（isAlert=true）はバッチが複数回失敗し続けている行を示す。
 * 赤色ハイライトで視覚的に警告する。</p>
 *
 * <p>Phase F 追加: 詳細モーダルの PENDING ドメイン行に「再試行」ボタンを追加。
 * retry 成功時は緑色トースト + 詳細再取得、失敗時は赤色トースト + retryCount 更新。
 * 一覧テーブルには retryCount 列を追加（>0 はオレンジ色で強調）。</p>
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const notification = useNotification()
const gdprApi = useSystemAdminGdprApi()
const { formatDateTime } = useDatetime()

// ===== 状態 =====
const summary = ref<GdprPurgeSummaryData | null>(null)
const summaryLoading = ref(false)

const listData = ref<GdprPurgeStatusPage | null>(null)
const listLoading = ref(false)

const detailDialogOpen = ref(false)
const detailLoading = ref(false)
const detailUserId = ref<number | null>(null)
const detailRows = ref<GdprPurgeStatusRow[]>([])

/** Phase F: retry 中のドメイン名（詳細モーダル内のみ）*/
const retryingDomain = ref<string | null>(null)

// ===== フィルター =====
const filterStatus = ref<string>('')
const filterDomain = ref<string>('')
const filterDateFrom = ref<string>('')
const filterDateTo = ref<string>('')
const currentPage = ref(0)
const pageSize = 20

// ===== 選択肢 =====
interface FilterOption {
  label: string
  value: string
}

const statusOptions = computed<FilterOption[]>(() => [
  { label: t('systemAdmin.gdpr.filter.allStatuses'), value: '' },
  { label: t('systemAdmin.gdpr.status.PENDING'), value: 'PENDING' },
  { label: t('systemAdmin.gdpr.status.SUCCESS'), value: 'SUCCESS' },
])

const domainOptions = computed<FilterOption[]>(() => [
  { label: t('systemAdmin.gdpr.filter.allDomains'), value: '' },
  { label: t('systemAdmin.gdpr.domain.role'), value: 'role' },
  { label: t('systemAdmin.gdpr.domain.team'), value: 'team' },
  { label: t('systemAdmin.gdpr.domain.payment'), value: 'payment' },
  { label: t('systemAdmin.gdpr.domain.chart'), value: 'chart' },
  { label: t('systemAdmin.gdpr.domain.proxy'), value: 'proxy' },
  { label: t('systemAdmin.gdpr.domain.errorreport'), value: 'errorreport' },
])

// ===== API 呼び出し =====
async function loadSummary() {
  summaryLoading.value = true
  try {
    const res = await gdprApi.getPurgeSummary()
    summary.value = res.data
  } catch (e) {
    console.error('gdpr/index.vue: failed to load summary', e)
    notification.error(t('systemAdmin.gdpr.toast.loadFailed'))
    summary.value = null
  } finally {
    summaryLoading.value = false
  }
}

async function loadList() {
  listLoading.value = true
  try {
    const params: GdprPurgeStatusQuery = {
      page: currentPage.value,
      size: pageSize,
    }
    if (filterStatus.value) params.status = filterStatus.value
    if (filterDomain.value) params.domain = filterDomain.value
    if (filterDateFrom.value) params.dateFrom = filterDateFrom.value
    if (filterDateTo.value) params.dateTo = filterDateTo.value

    const res = await gdprApi.listPurgeStatus(params)
    listData.value = res.data
  } catch (e) {
    console.error('gdpr/index.vue: failed to load list', e)
    notification.error(t('systemAdmin.gdpr.toast.loadFailed'))
    listData.value = null
  } finally {
    listLoading.value = false
  }
}

async function load() {
  await Promise.all([loadSummary(), loadList()])
}

async function search() {
  currentPage.value = 0
  await loadList()
}

async function onPageChange(event: { page: number }) {
  currentPage.value = event.page
  await loadList()
}

async function openDetail(row: GdprPurgeStatusRow) {
  detailUserId.value = row.userId
  detailRows.value = []
  detailDialogOpen.value = true
  detailLoading.value = true
  try {
    const res = await gdprApi.getUserPurgeDetail(row.userId)
    detailRows.value = res.data
  } catch (e) {
    console.error('gdpr/index.vue: failed to load user detail', e)
    notification.error(t('systemAdmin.gdpr.toast.detailLoadFailed'))
  } finally {
    detailLoading.value = false
  }
}

function closeDetail() {
  detailDialogOpen.value = false
  detailUserId.value = null
  detailRows.value = []
  retryingDomain.value = null
}

function downloadCsv() {
  window.location.href = gdprApi.getExportUrl()
}

/**
 * Phase F: PENDING ドメインの手動 retry を実行する。
 *
 * 確認ダイアログを経由し、成功・失敗それぞれでトーストを表示する。
 * いずれの場合もメイン一覧を再取得してサマリー数字を更新する。
 */
async function retryDomain(row: GdprPurgeStatusRow) {
  const confirmed = window.confirm(
    t('systemAdmin.gdpr.retry.confirm', { domain: row.domainName }),
  )
  if (!confirmed) return

  retryingDomain.value = row.domainName
  try {
    const res = await gdprApi.retryDomainPurge(row.userId, row.domainName)
    const result = res.data

    if (result.succeeded) {
      if (result.retryCount === 0) {
        notification.info(t('systemAdmin.gdpr.retry.alreadySuccess'))
      } else {
        notification.success(t('systemAdmin.gdpr.retry.success'))
      }
    } else {
      notification.error(t('systemAdmin.gdpr.retry.failed'))
    }

    // 詳細行を再取得してモーダル内を更新する
    if (detailUserId.value !== null) {
      try {
        const detailRes = await gdprApi.getUserPurgeDetail(detailUserId.value)
        detailRows.value = detailRes.data
      } catch (e) {
        console.error('gdpr/index.vue: failed to reload user detail after retry', e)
      }
    }

    // サマリーカードと一覧テーブルも更新する
    await Promise.all([loadSummary(), loadList()])
  } catch (e) {
    console.error('gdpr/index.vue: failed to retry domain purge', e)
    notification.error(t('systemAdmin.gdpr.toast.retryFailed'))
  } finally {
    retryingDomain.value = null
  }
}

// ===== ユーティリティ =====

function hashShort(hash: string): string {
  return hash.length > 8 ? `${hash.slice(0, 8)}...` : hash
}

function statusSeverity(row: GdprPurgeStatusRow): 'success' | 'warn' | 'danger' {
  if (row.status === 'SUCCESS') return 'success'
  if (row.isAlert) return 'danger'
  return 'warn'
}

function statusLabel(row: GdprPurgeStatusRow): string {
  return t(`systemAdmin.gdpr.status.${row.status}`)
}

function domainLabel(domain: string): string {
  const key = `systemAdmin.gdpr.domain.${domain}`
  const translated = t(key)
  return translated === key ? domain : translated
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-screen-xl space-y-6 p-4">
    <!-- ヘッダー -->
    <header class="flex items-center justify-between">
      <div>
        <span
          class="rounded-full bg-red-100 px-2.5 py-0.5 text-xs font-semibold text-red-600 dark:bg-red-900/30 dark:text-red-400"
        >
          SYSTEM ADMIN
        </span>
        <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
          {{ t('systemAdmin.gdpr.title') }}
        </h1>
      </div>
      <div class="flex items-center gap-2">
        <Button
          :label="t('systemAdmin.gdpr.export')"
          icon="pi pi-download"
          severity="secondary"
          size="small"
          @click="downloadCsv"
        />
        <Button
          v-tooltip.left="t('systemAdmin.gdpr.filter.search')"
          icon="pi pi-refresh"
          text
          rounded
          :loading="listLoading || summaryLoading"
          @click="load"
        />
      </div>
    </header>

    <!-- サマリーカード -->
    <div v-if="summaryLoading" class="flex items-center justify-center py-8">
      <i class="pi pi-spin pi-spinner mr-2 text-2xl text-surface-400" aria-hidden="true" />
    </div>
    <template v-else-if="summary">
      <div class="grid grid-cols-3 gap-4">
        <!-- 処理完了 -->
        <Card>
          <template #content>
            <div class="flex flex-col items-center gap-1 py-2">
              <span class="text-sm text-surface-500">{{ t('systemAdmin.gdpr.summary.completed') }}</span>
              <span class="text-3xl font-bold text-green-600">{{ summary.totalSuccess }}</span>
            </div>
          </template>
        </Card>
        <!-- PENDING -->
        <Card>
          <template #content>
            <div class="flex flex-col items-center gap-1 py-2">
              <span class="text-sm text-surface-500">{{ t('systemAdmin.gdpr.summary.pending') }}</span>
              <span class="text-3xl font-bold text-yellow-600">{{ summary.totalPending }}</span>
            </div>
          </template>
        </Card>
        <!-- アラート -->
        <Card :class="summary.alertCount > 0 ? 'ring-2 ring-red-500' : ''">
          <template #content>
            <div class="flex flex-col items-center gap-1 py-2">
              <span class="text-sm text-surface-500">{{ t('systemAdmin.gdpr.summary.alert') }}</span>
              <span
                class="text-3xl font-bold"
                :class="summary.alertCount > 0 ? 'text-red-600' : 'text-surface-700 dark:text-surface-200'"
              >
                {{ summary.alertCount }}
              </span>
              <Badge
                v-if="summary.alertCount > 0"
                :value="summary.alertCount"
                severity="danger"
                class="mt-1"
              />
            </div>
          </template>
        </Card>
      </div>

      <!-- ドメイン別サマリー -->
      <Card>
        <template #title>
          <span class="text-sm font-semibold text-surface-600 dark:text-surface-300">
            {{ t('systemAdmin.gdpr.table.domainName') }}
          </span>
        </template>
        <template #content>
          <DataTable :value="summary.byDomain" class="text-sm">
            <Column field="domain" :header="t('systemAdmin.gdpr.table.domainName')">
              <template #body="{ data: row }: { data: { domain: string; pendingCount: number; successCount: number } }">
                {{ domainLabel(row.domain) }}
              </template>
            </Column>
            <Column field="pendingCount" :header="t('systemAdmin.gdpr.status.PENDING')">
              <template #body="{ data: row }: { data: { domain: string; pendingCount: number; successCount: number } }">
                <span :class="row.pendingCount > 0 ? 'font-semibold text-yellow-600' : 'text-surface-500'">
                  {{ row.pendingCount }}
                </span>
              </template>
            </Column>
            <Column field="successCount" :header="t('systemAdmin.gdpr.status.SUCCESS')">
              <template #body="{ data: row }: { data: { domain: string; pendingCount: number; successCount: number } }">
                <span class="text-green-600">{{ row.successCount }}</span>
              </template>
            </Column>
          </DataTable>
        </template>
      </Card>
    </template>

    <!-- フィルター -->
    <Card>
      <template #content>
        <div class="flex flex-wrap items-end gap-4">
          <div class="flex flex-col gap-1.5">
            <label class="text-sm font-medium text-surface-600 dark:text-surface-300">
              {{ t('systemAdmin.gdpr.filter.status') }}
            </label>
            <Select
              v-model="filterStatus"
              :options="statusOptions"
              option-label="label"
              option-value="value"
              class="w-40"
            />
          </div>
          <div class="flex flex-col gap-1.5">
            <label class="text-sm font-medium text-surface-600 dark:text-surface-300">
              {{ t('systemAdmin.gdpr.filter.domain') }}
            </label>
            <Select
              v-model="filterDomain"
              :options="domainOptions"
              option-label="label"
              option-value="value"
              class="w-44"
            />
          </div>
          <div class="flex flex-col gap-1.5">
            <label class="text-sm font-medium text-surface-600 dark:text-surface-300">
              {{ t('systemAdmin.gdpr.filter.dateFrom') }}
            </label>
            <InputText v-model="filterDateFrom" type="datetime-local" class="w-48 text-sm" />
          </div>
          <div class="flex flex-col gap-1.5">
            <label class="text-sm font-medium text-surface-600 dark:text-surface-300">
              {{ t('systemAdmin.gdpr.filter.dateTo') }}
            </label>
            <InputText v-model="filterDateTo" type="datetime-local" class="w-48 text-sm" />
          </div>
          <Button
            :label="t('systemAdmin.gdpr.filter.search')"
            icon="pi pi-search"
            :loading="listLoading"
            @click="search"
          />
        </div>
      </template>
    </Card>

    <!-- 一覧テーブル -->
    <div v-if="listLoading" class="flex items-center justify-center py-12">
      <i class="pi pi-spin pi-spinner mr-2 text-2xl text-surface-400" aria-hidden="true" />
    </div>
    <template v-else-if="listData && listData.content.length > 0">
      <DataTable
        :value="listData.content"
        striped-rows
        class="cursor-pointer text-sm"
        data-test="gdpr-table"
        :row-class="(row: GdprPurgeStatusRow) => row.isAlert ? 'bg-red-50 dark:bg-red-900/20' : ''"
        @row-click="openDetail($event.data)"
      >
        <Column field="userId" :header="t('systemAdmin.gdpr.table.userId')" style="width: 8rem" />
        <Column field="emailHash" :header="t('systemAdmin.gdpr.table.emailHash')" style="min-width: 10rem">
          <template #body="{ data: row }: { data: GdprPurgeStatusRow }">
            <span class="font-mono text-xs text-surface-500">{{ hashShort(row.emailHash) }}</span>
          </template>
        </Column>
        <Column field="domainName" :header="t('systemAdmin.gdpr.table.domainName')" style="width: 9rem">
          <template #body="{ data: row }: { data: GdprPurgeStatusRow }">
            {{ domainLabel(row.domainName) }}
          </template>
        </Column>
        <Column field="status" :header="t('systemAdmin.gdpr.table.status')" style="width: 8rem">
          <template #body="{ data: row }: { data: GdprPurgeStatusRow }">
            <Tag :value="statusLabel(row)" :severity="statusSeverity(row)" />
          </template>
        </Column>
        <Column field="attemptedAt" :header="t('systemAdmin.gdpr.table.attemptedAt')" style="width: 12rem">
          <template #body="{ data: row }: { data: GdprPurgeStatusRow }">
            {{ formatDateTime(row.attemptedAt) }}
          </template>
        </Column>
        <Column field="completedAt" :header="t('systemAdmin.gdpr.table.completedAt')" style="width: 12rem">
          <template #body="{ data: row }: { data: GdprPurgeStatusRow }">
            {{ formatDateTime(row.completedAt) }}
          </template>
        </Column>
        <!-- Phase F: retry 回数列（1回以上はオレンジ強調） -->
        <Column field="retryCount" :header="t('systemAdmin.gdpr.table.retryCount')" style="width: 7rem">
          <template #body="{ data: row }: { data: GdprPurgeStatusRow }">
            <span
              :class="row.retryCount > 0 ? 'font-semibold text-orange-500' : 'text-surface-400'"
            >
              {{ row.retryCount }}
            </span>
          </template>
        </Column>
        <Column field="isAlert" :header="t('systemAdmin.gdpr.table.isAlert')" style="width: 7rem">
          <template #body="{ data: row }: { data: GdprPurgeStatusRow }">
            <i v-if="row.isAlert" class="pi pi-exclamation-triangle text-red-500" aria-hidden="true" />
            <span v-else class="text-surface-400">-</span>
          </template>
        </Column>
      </DataTable>

      <!-- ページネーション -->
      <Paginator
        :rows="pageSize"
        :total-records="listData.totalElements"
        :first="currentPage * pageSize"
        @page="onPageChange"
      />
    </template>
    <div
      v-else
      class="flex flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-surface-300 py-16 text-surface-400 dark:border-surface-600"
    >
      <i class="pi pi-inbox text-4xl" aria-hidden="true" />
      <p class="text-sm">{{ t('systemAdmin.gdpr.noData') }}</p>
    </div>

    <!-- 詳細モーダル -->
    <Dialog
      v-model:visible="detailDialogOpen"
      modal
      :header="t('systemAdmin.gdpr.detail.title')"
      :style="{ width: '52rem' }"
      :draggable="false"
      @hide="closeDetail"
    >
      <div v-if="detailLoading" class="flex items-center justify-center py-8">
        <i class="pi pi-spin pi-spinner text-2xl text-surface-400" aria-hidden="true" />
      </div>
      <template v-else>
        <p v-if="detailUserId" class="mb-3 text-sm text-surface-500">
          {{ t('systemAdmin.gdpr.table.userId') }}: <strong>{{ detailUserId }}</strong>
        </p>
        <div
          v-if="detailRows.length === 0"
          class="rounded border border-dashed border-surface-300 px-4 py-6 text-center text-sm text-surface-400 dark:border-surface-600"
        >
          {{ t('systemAdmin.gdpr.noData') }}
        </div>
        <DataTable
          v-else
          :value="detailRows"
          class="text-sm"
          :row-class="(row: GdprPurgeStatusRow) => row.isAlert ? 'bg-red-50 dark:bg-red-900/20' : ''"
        >
          <Column field="domainName" :header="t('systemAdmin.gdpr.table.domainName')">
            <template #body="{ data: row }: { data: GdprPurgeStatusRow }">
              {{ domainLabel(row.domainName) }}
            </template>
          </Column>
          <Column field="status" :header="t('systemAdmin.gdpr.table.status')" style="width: 8rem">
            <template #body="{ data: row }: { data: GdprPurgeStatusRow }">
              <Tag :value="statusLabel(row)" :severity="statusSeverity(row)" />
            </template>
          </Column>
          <Column field="attemptedAt" :header="t('systemAdmin.gdpr.table.attemptedAt')" style="width: 12rem">
            <template #body="{ data: row }: { data: GdprPurgeStatusRow }">
              {{ formatDateTime(row.attemptedAt) }}
            </template>
          </Column>
          <Column field="completedAt" :header="t('systemAdmin.gdpr.table.completedAt')" style="width: 12rem">
            <template #body="{ data: row }: { data: GdprPurgeStatusRow }">
              {{ formatDateTime(row.completedAt) }}
            </template>
          </Column>
          <Column field="isAlert" :header="t('systemAdmin.gdpr.table.isAlert')" style="width: 6rem">
            <template #body="{ data: row }: { data: GdprPurgeStatusRow }">
              <i v-if="row.isAlert" class="pi pi-exclamation-triangle text-red-500" aria-hidden="true" />
              <span v-else class="text-surface-400">-</span>
            </template>
          </Column>
          <!-- Phase F: retry 情報 + ボタン列 -->
          <Column :header="t('systemAdmin.gdpr.table.retryCount')" style="width: 14rem">
            <template #body="{ data: row }: { data: GdprPurgeStatusRow }">
              <div class="flex flex-col gap-1">
                <!-- retry 回数 -->
                <span
                  v-if="row.retryCount > 0"
                  class="text-xs text-orange-500"
                >
                  {{ t('systemAdmin.gdpr.retry.count', { count: row.retryCount }) }}
                </span>
                <!-- 最終 retry 日時 -->
                <span
                  v-if="row.lastRetriedAt"
                  class="text-xs text-surface-400"
                >
                  {{ t('systemAdmin.gdpr.retry.lastRetried', { datetime: formatDateTime(row.lastRetriedAt) }) }}
                </span>
                <!-- retry ボタン（PENDING のみ表示） -->
                <Button
                  v-if="row.status === 'PENDING'"
                  :label="t('systemAdmin.gdpr.retry.button')"
                  icon="pi pi-refresh"
                  severity="warning"
                  size="small"
                  :loading="retryingDomain === row.domainName"
                  :disabled="retryingDomain !== null"
                  class="mt-1 w-full"
                  @click="retryDomain(row)"
                />
              </div>
            </template>
          </Column>
        </DataTable>
      </template>
      <template #footer>
        <Button :label="t('systemAdmin.gdpr.detail.close')" text @click="closeDetail" />
      </template>
    </Dialog>
  </div>
</template>
