<script setup lang="ts">
/**
 * F09.17 Phase 11-c-4 — 広告主向け メッセージ型キャンペーン パフォーマンスレポート。
 *
 * <p>期間指定で配信実績を集計表示。KPI カード 4 枚 + 日次推移グラフ (chart.js)
 * + チャネル別ブレークダウン表 + CSV エクスポートボタン。</p>
 */
import dayjs from 'dayjs'
import type { AdCampaignReport } from '~/types/adMessagingCampaign'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const route = useRoute()
const { t } = useI18n()
const reportApi = useAdMessagingCampaignReportApi()
const notification = useNotification()

const campaignId = computed(() => String(route.params.campaignId))
const { userTimezone } = useDatetime()

const loading = ref(true)
const report = ref<AdCampaignReport | null>(null)
const exportingCsv = ref(false)

// 既定期間: 直近 30 日（ユーザーTZで「今日」を計算）
const todayInTz = dayjs().tz(userTimezone.value)
const fromDate = ref<Date>(todayInTz.subtract(30, 'day').toDate())
const toDate = ref<Date>(todayInTz.toDate())

function formatYmd(d: Date): string {
  return dayjs.tz(d, userTimezone.value).format('YYYY-MM-DD')
}

async function load() {
  loading.value = true
  try {
    const res = await reportApi.getCampaignReport(campaignId.value, {
      from: formatYmd(fromDate.value),
      to: formatYmd(toDate.value),
    })
    report.value = res.data
  } catch {
    notification.error(t('advertising.pages.system_admin_dashboard.load_failed'))
    report.value = null
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function handleExportCsv() {
  exportingCsv.value = true
  try {
    const blob = await reportApi.exportReportCsv(campaignId.value, {
      from: formatYmd(fromDate.value),
      to: formatYmd(toDate.value),
    })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `messaging-campaign-${campaignId.value}-report.csv`
    a.click()
    URL.revokeObjectURL(url)
    notification.success(t('advertising.pages.advertiser_campaign_report.export_started'))
  } catch {
    notification.error(t('advertising.pages.system_admin_dashboard.load_failed'))
  } finally {
    exportingCsv.value = false
  }
}
</script>

<template>
  <div>
    <div class="mb-6 flex flex-wrap items-center justify-between gap-3">
      <div>
        <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
          {{ t('advertising.pages.advertiser_campaign_report.title') }}
        </h1>
        <p class="mt-0.5 text-sm text-surface-500">
          {{ t('advertising.pages.advertiser_campaign_report.description') }}
        </p>
      </div>
      <Button
        :label="t('advertising.actions.export_csv')"
        icon="pi pi-download"
        :loading="exportingCsv"
        :disabled="!report || loading"
        data-testid="export-csv-button"
        @click="handleExportCsv"
      />
    </div>

    <!-- 期間フィルタ -->
    <div class="mb-6 flex flex-wrap items-end gap-4">
      <div>
        <label class="mb-1 block text-xs font-medium text-surface-600 dark:text-surface-300">
          {{ t('advertising.pages.advertiser_campaign_report.field_from') }}
        </label>
        <DatePicker
          v-model="fromDate"
          date-format="yy-mm-dd"
          show-icon
          data-testid="report-from-date"
        />
      </div>
      <div>
        <label class="mb-1 block text-xs font-medium text-surface-600 dark:text-surface-300">
          {{ t('advertising.pages.advertiser_campaign_report.field_to') }}
        </label>
        <DatePicker
          v-model="toDate"
          date-format="yy-mm-dd"
          show-icon
          data-testid="report-to-date"
        />
      </div>
      <Button
        :label="t('advertising.actions.reload')"
        icon="pi pi-refresh"
        severity="secondary"
        :loading="loading"
        @click="load"
      />
    </div>

    <PageLoading v-if="loading" />

    <template v-else-if="report">
      <!-- KPI -->
      <div class="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <div
          class="rounded-lg border border-surface-200 bg-white p-4 dark:border-surface-700 dark:bg-surface-800"
          data-testid="kpi-delivered"
        >
          <p class="text-xs text-surface-500">
            {{ t('advertising.pages.advertiser_campaign_report.kpi_delivered') }}
          </p>
          <p class="mt-2 text-3xl font-bold text-primary-700 dark:text-primary-300">
            {{ report.totals.delivered.toLocaleString() }}
          </p>
        </div>
        <div
          class="rounded-lg border border-surface-200 bg-white p-4 dark:border-surface-700 dark:bg-surface-800"
          data-testid="kpi-opened"
        >
          <p class="text-xs text-surface-500">
            {{ t('advertising.pages.advertiser_campaign_report.kpi_opened') }}
          </p>
          <p class="mt-2 text-3xl font-bold text-emerald-600 dark:text-emerald-400">
            {{ report.totals.opened.toLocaleString() }}
          </p>
        </div>
        <div
          class="rounded-lg border border-surface-200 bg-white p-4 dark:border-surface-700 dark:bg-surface-800"
          data-testid="kpi-clicked"
        >
          <p class="text-xs text-surface-500">
            {{ t('advertising.pages.advertiser_campaign_report.kpi_clicked') }}
          </p>
          <p class="mt-2 text-3xl font-bold text-orange-600 dark:text-orange-400">
            {{ report.totals.clicked.toLocaleString() }}
          </p>
        </div>
        <div
          class="rounded-lg border border-surface-200 bg-white p-4 dark:border-surface-700 dark:bg-surface-800"
          data-testid="kpi-consumed"
        >
          <p class="text-xs text-surface-500">
            {{ t('advertising.pages.advertiser_campaign_report.kpi_consumed') }}
          </p>
          <p class="mt-2 text-3xl font-bold text-surface-800 dark:text-surface-100">
            ¥{{ report.totals.consumedBudgetYen.toLocaleString() }}
          </p>
        </div>
      </div>

      <!-- 日次推移チャート -->
      <section class="mb-6 rounded-lg border border-surface-200 bg-white p-4 dark:border-surface-700 dark:bg-surface-800">
        <h2 class="mb-3 text-base font-semibold text-surface-900 dark:text-surface-50">
          {{ t('advertising.pages.advertiser_campaign_report.chart_title') }}
        </h2>
        <div v-if="report.daily.length === 0" class="py-12 text-center text-sm text-surface-500">
          {{ t('advertising.pages.advertiser_campaign_report.no_data') }}
        </div>
        <AdReportChart v-else :daily="report.daily" />
      </section>

      <!-- チャネル別ブレークダウン -->
      <section class="rounded-lg border border-surface-200 bg-white p-4 dark:border-surface-700 dark:bg-surface-800">
        <h2 class="mb-3 text-base font-semibold text-surface-900 dark:text-surface-50">
          {{ t('advertising.pages.advertiser_campaign_report.channel_breakdown_title') }}
        </h2>
        <DataTable :value="report.byChannel" striped-rows data-testid="channel-breakdown-table">
          <Column field="channelType" :header="t('advertising.pages.system_admin_moderation.user_reports_section')">
            <template #body="{ data }">
              {{ t(`advertising.channel.${data.channelType.toLowerCase()}`) }}
            </template>
          </Column>
          <Column
            field="delivered"
            :header="t('advertising.pages.advertiser_campaign_report.channel_breakdown_delivered')"
          />
          <Column
            field="opened"
            :header="t('advertising.pages.advertiser_campaign_report.channel_breakdown_opened')"
          />
          <Column
            field="clicked"
            :header="t('advertising.pages.advertiser_campaign_report.channel_breakdown_clicked')"
          />
        </DataTable>
      </section>
    </template>

    <div
      v-else
      class="rounded-lg border border-dashed border-surface-300 bg-surface-50 px-6 py-12 text-center text-sm text-surface-500 dark:border-surface-700 dark:bg-surface-800"
    >
      {{ t('advertising.pages.advertiser_campaign_report.no_data') }}
    </div>
  </div>
</template>
