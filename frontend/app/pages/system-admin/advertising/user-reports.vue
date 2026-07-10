<script setup lang="ts">
/**
 * F09.17 Phase 11-c-4 — SYSTEM_ADMIN 広告ユーザー通報一覧。
 *
 * <p>ユーザーから通報された広告キャンペーン一覧を表示する。
 * `autoSuspendCandidate=true` のキャンペーンは黄→赤のハイライトで強調する。
 * 行クリックで該当キャンペーン詳細（審査ページ）へ遷移する。</p>
 */
import type { AdUserReport } from '~/types/adModeration'
import type { AdReportReason } from '~/types/adPreferences'
import type { AdUserReportListParams } from '~/composables/useSystemAdminAdCampaignApi'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { formatDateTime } = useDatetime()
const router = useRouter()
const systemAdminAdApi = useSystemAdminAdCampaignApi()
const notification = useNotification()

const loading = ref(true)
const reports = ref<AdUserReport[]>([])
const totalElements = ref(0)
const page = ref(0)
const pageSize = ref(20)

const filterReason = ref<AdReportReason | undefined>(undefined)
const filterStatus = ref<AdUserReport['status'] | undefined>(undefined)

const reasonOptions = computed(() => [
  { label: t('advertising.report_reason.offensive'), value: 'OFFENSIVE' as const },
  { label: t('advertising.report_reason.misleading'), value: 'MISLEADING' as const },
  { label: t('advertising.report_reason.spam'), value: 'SPAM' as const },
  { label: t('advertising.report_reason.irrelevant'), value: 'IRRELEVANT' as const },
  { label: t('advertising.report_reason.other'), value: 'OTHER' as const },
])

const statusOptions = [
  { label: 'NEW', value: 'NEW' as const },
  { label: 'REVIEWING', value: 'REVIEWING' as const },
  { label: 'RESOLVED', value: 'RESOLVED' as const },
  { label: 'DISMISSED', value: 'DISMISSED' as const },
]

async function load() {
  loading.value = true
  try {
    const params: AdUserReportListParams = {
      page: page.value,
      size: pageSize.value,
    }
    if (filterReason.value) params.reason = filterReason.value
    if (filterStatus.value) params.status = filterStatus.value
    const res = await systemAdminAdApi.listUserReports(params)
    reports.value = res.data
    totalElements.value = res.meta.total ?? res.meta.totalElements ?? 0
  } catch {
    notification.error(t('advertising.pages.system_admin_dashboard.load_failed'))
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch([filterReason, filterStatus], () => {
  page.value = 0
  load()
})

function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  pageSize.value = event.rows
  load()
}

function goCampaign(row: AdUserReport) {
  // メッセージ型（UUID）は審査キュー詳細へ、運用型（数値）は運用型審査キューへ遷移する。
  if (row.campaignId) {
    router.push(`/system-admin/advertising/moderation-queue/${row.campaignId}`)
  } else if (row.operationalCampaignId != null) {
    router.push('/system-admin/advertising/operational-queue')
  }
}

/** 種別・ID 表示（メッセージ型 = UUID 先頭 8 桁、運用型 = #数値）。 */
function targetLabel(row: AdUserReport): string {
  if (row.campaignId) return `${row.campaignId.slice(0, 8)}…`
  if (row.operationalCampaignId != null) return `#${row.operationalCampaignId}`
  return '-'
}

async function changeStatus(row: AdUserReport, status: AdUserReport['status']) {
  try {
    await systemAdminAdApi.updateUserReportStatus(row.id, status)
    await load()
  } catch {
    notification.error(t('advertising.pages.system_admin_user_reports.status_update_failed'))
  }
}

function rowClasses(row: AdUserReport): string {
  if (!row.autoSuspendCandidate) return ''
  // ハイライト強度は固定（赤）。閾値別差をつけたい場合は backend に severity フィールドが必要。
  return 'bg-red-50 hover:bg-red-100 dark:bg-red-950/30 dark:hover:bg-red-900/40'
}
</script>

<template>
  <div class="mx-auto max-w-screen-xl">
    <div class="mb-6 flex items-center justify-between">
      <div>
        <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
          {{ t('advertising.pages.system_admin_user_reports.title') }}
        </h1>
        <p class="mt-0.5 text-sm text-surface-500">
          {{ t('advertising.pages.system_admin_user_reports.description') }}
        </p>
      </div>
      <Button
        v-tooltip.left="t('advertising.actions.reload')"
        icon="pi pi-refresh"
        text
        rounded
        :loading="loading"
        @click="load"
      />
    </div>

    <!-- フィルタ -->
    <div class="mb-4 flex flex-wrap items-end gap-4">
      <div>
        <label class="mb-1 block text-xs font-medium text-surface-600 dark:text-surface-300">
          {{ t('advertising.pages.system_admin_user_reports.filter_reason') }}
        </label>
        <Select
          v-model="filterReason"
          :options="reasonOptions"
          option-label="label"
          option-value="value"
          show-clear
          placeholder="-"
          data-testid="filter-reason"
        />
      </div>
      <div>
        <label class="mb-1 block text-xs font-medium text-surface-600 dark:text-surface-300">
          {{ t('advertising.pages.system_admin_user_reports.filter_status') }}
        </label>
        <Select
          v-model="filterStatus"
          :options="statusOptions"
          option-label="label"
          option-value="value"
          show-clear
          placeholder="-"
          data-testid="filter-status"
        />
      </div>
    </div>

    <PageLoading v-if="loading" />

    <template v-else>
      <div
        v-if="reports.length === 0"
        class="rounded-lg border border-dashed border-surface-300 bg-surface-50 px-6 py-12 text-center text-sm text-surface-500 dark:border-surface-700 dark:bg-surface-800"
        data-testid="user-reports-empty"
      >
        {{ t('advertising.pages.system_admin_user_reports.empty') }}
      </div>

      <DataTable
        v-else
        :value="reports"
        :row-class="rowClasses"
        data-testid="user-reports-table"
        striped-rows
      >
        <Column
          field="reportedAt"
          :header="t('advertising.pages.system_admin_user_reports.column_reported_at')"
        >
          <template #body="{ data }: { data: AdUserReport }">
            {{ formatDateTime(data.reportedAt) }}
          </template>
        </Column>
        <Column
          field="campaignId"
          :header="t('advertising.pages.system_admin_user_reports.column_campaign')"
        >
          <template #body="{ data }: { data: AdUserReport }">
            <div class="flex items-center gap-2">
              <Tag
                :value="data.campaignId
                  ? t('advertising.pages.system_admin_user_reports.type_messaging')
                  : t('advertising.pages.system_admin_user_reports.type_operational')"
                severity="secondary"
                data-testid="report-type-badge"
              />
              <code class="text-xs text-surface-700 dark:text-surface-200">
                {{ targetLabel(data) }}
              </code>
              <Tag
                v-if="data.autoSuspendCandidate"
                v-tooltip.top="t('advertising.pages.system_admin_user_reports.auto_suspend_candidate_tooltip')"
                :value="t('advertising.pages.system_admin_user_reports.auto_suspend_candidate')"
                severity="danger"
                data-testid="auto-suspend-tag"
              />
            </div>
          </template>
        </Column>
        <Column
          field="userId"
          :header="t('advertising.pages.system_admin_user_reports.column_reporter')"
        >
          <template #body="{ data }: { data: AdUserReport }">
            {{ data.userId }}
          </template>
        </Column>
        <Column
          field="reason"
          :header="t('advertising.pages.system_admin_user_reports.column_reason')"
        >
          <template #body="{ data }: { data: AdUserReport }">
            {{ t(`advertising.report_reason.${data.reason.toLowerCase()}`) }}
          </template>
        </Column>
        <Column
          field="status"
          :header="t('advertising.pages.system_admin_user_reports.column_status')"
        >
          <template #body="{ data }: { data: AdUserReport }">
            <Tag :value="data.status" />
          </template>
        </Column>
        <Column>
          <template #body="{ data }: { data: AdUserReport }">
            <div class="flex flex-wrap items-center gap-2">
              <Button
                v-if="data.status === 'NEW'"
                size="small"
                :label="t('advertising.pages.system_admin_user_reports.mark_reviewing')"
                severity="secondary"
                outlined
                data-testid="mark-reviewing"
                @click="changeStatus(data, 'REVIEWING')"
              />
              <Button
                v-if="data.status === 'REVIEWING'"
                size="small"
                :label="t('advertising.pages.system_admin_user_reports.mark_resolved')"
                severity="success"
                outlined
                data-testid="mark-resolved"
                @click="changeStatus(data, 'RESOLVED')"
              />
              <Button
                v-if="data.status === 'REVIEWING'"
                size="small"
                :label="t('advertising.pages.system_admin_user_reports.mark_dismissed')"
                severity="secondary"
                outlined
                data-testid="mark-dismissed"
                @click="changeStatus(data, 'DISMISSED')"
              />
              <Button
                size="small"
                :label="t('advertising.pages.system_admin_user_reports.go_campaign')"
                icon="pi pi-arrow-right"
                icon-pos="right"
                severity="secondary"
                text
                @click="goCampaign(data)"
              />
            </div>
          </template>
        </Column>
      </DataTable>

      <div v-if="totalElements > pageSize" class="mt-6 flex justify-center">
        <Paginator
          :rows="pageSize"
          :total-records="totalElements"
          :rows-per-page-options="[10, 20, 50]"
          @page="onPage"
        />
      </div>
    </template>
  </div>
</template>
