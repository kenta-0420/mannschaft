<script setup lang="ts">
import type {
  CampaignPerformanceResponse,
  CreativeComparisonResponse,
  BreakdownResponse,
} from '~/types/advertiser'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const { handleApiError } = useErrorHandler()
const loadFailed = ref(false)
const route = useRoute()
const orgSlug = String(route.params.slug)
const campaignId = Number(route.params.campaignId)
const advertiserApi = useAdvertiserApi()

const loading = ref(true)
const performance = ref<CampaignPerformanceResponse | null>(null)
const creatives = ref<CreativeComparisonResponse | null>(null)
const breakdown = ref<BreakdownResponse | null>(null)
const exportingCsv = ref(false)

const now = new Date()
const thirtyDaysAgo = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000)
const dateFrom = ref<Date>(thirtyDaysAgo)
const dateTo = ref<Date>(now)

function formatDate(d: Date): string {
  return toLocalDateString(d)
}

const statusSeverityMap: Record<string, 'success' | 'info' | 'warn' | 'danger' | 'secondary' | 'contrast'> = {
  ACTIVE: 'success',
  PAUSED: 'warn',
  DRAFT: 'secondary',
  PENDING_REVIEW: 'info',
  ENDED: 'danger',
}

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    const [perfRes, creativeRes, breakdownRes] = await Promise.all([
      advertiserApi.getCampaignPerformance(
        'ORGANIZATION',
        orgSlug,
        campaignId,
        formatDate(dateFrom.value),
        formatDate(dateTo.value),
      ),
      advertiserApi.getCreativeComparison(
        'ORGANIZATION',
        orgSlug,
        campaignId,
        formatDate(dateFrom.value),
        formatDate(dateTo.value),
      ),
      advertiserApi.getBreakdown(
        'ORGANIZATION',
        orgSlug,
        campaignId,
        formatDate(dateFrom.value),
        formatDate(dateTo.value),
      ),
    ])
    performance.value = perfRes.data
    creatives.value = creativeRes.data
    breakdown.value = breakdownRes.data
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

async function handleExportCsv() {
  exportingCsv.value = true
  try {
    const blob = await advertiserApi.exportCampaignCsv(
      'ORGANIZATION',
      orgSlug,
      campaignId,
      formatDate(dateFrom.value),
      formatDate(dateTo.value),
    )
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `campaign-${campaignId}-report.csv`
    a.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    handleApiError(error)
  } finally {
    exportingCsv.value = false
  }
}

onMounted(load)
watch([dateFrom, dateTo], load)
</script>

<template>
  <div>
    <PageHeader
      :title="performance?.campaignName ?? t('advertising.teams_page.campaign_detail.default_title')"
      :back-to="`/organizations/${orgSlug}/advertiser`"
    >
      <Tag
        v-if="performance"
        :value="performance.status"
        :severity="statusSeverityMap[performance.status] ?? 'secondary'"
      />
      <template #actions>
        <DatePicker v-model="dateFrom" date-format="yy-mm-dd" :placeholder="t('advertising.teams_page.campaign_detail.date_from_placeholder')" class="w-36" />
        <span class="text-surface-400">〜</span>
        <DatePicker v-model="dateTo" date-format="yy-mm-dd" :placeholder="t('advertising.teams_page.campaign_detail.date_to_placeholder')" class="w-36" />
        <Button
          icon="pi pi-download"
          :label="t('advertising.teams_page.campaign_detail.csv_button')"
          severity="secondary"
          size="small"
          :loading="exportingCsv"
          @click="handleExportCsv"
        />
      </template>
    </PageHeader>

    <div v-if="loading" class="flex justify-center py-20"><LoadingBounce /></div>

    <template v-else-if="performance && !loadFailed">
      <CampaignMetricsCards :performance="performance" />
      <CampaignDataTables
        :points="performance.points"
        :creatives="creatives"
        :breakdown="breakdown"
      />
    </template>

    <div v-else class="py-20 text-center">
      <DashboardErrorState
        :message="t('advertising.teams_page.campaign_detail.load_failed')"
        @retry="load"
      />
      <NuxtLink :to="`/organizations/${orgSlug}/advertiser`">
        <Button
          :label="t('advertising.teams_page.campaign_detail.back_to_dashboard')"
          icon="pi pi-arrow-left"
          severity="secondary"
          class="mt-4"
        />
      </NuxtLink>
    </div>
  </div>
</template>
