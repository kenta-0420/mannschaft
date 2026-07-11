<script setup lang="ts">
// F09.19.6 チームスコープ キャンペーン詳細（実績レポート）ページ。
// 組織版 (pages/organizations/[slug]/advertiser/campaigns/[campaignId].vue) を team scope で読み替えたもの。
// F09.19.5b で新設された team scope の performance/creatives(比較)/breakdown/export API を使う。
//
// 組織版は PageHeader を使わず BackButton を手組みしていたが、本ページは新規作成のため
// /統一 方針（PageHeader の badge スロット + actions スロット）に合わせて構成する。

import type {
  CampaignPerformanceResponse,
  CreativeComparisonResponse,
  BreakdownResponse,
} from '~/types/advertiser'

definePageMeta({ layout: 'team', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamSlug = String(route.params.slug)
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
  return d.toISOString().slice(0, 10)
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
  try {
    const [perfRes, creativeRes, breakdownRes] = await Promise.all([
      advertiserApi.getCampaignPerformance(
        'TEAM',
        teamSlug,
        campaignId,
        formatDate(dateFrom.value),
        formatDate(dateTo.value),
      ),
      advertiserApi.getCreativeComparison(
        'TEAM',
        teamSlug,
        campaignId,
        formatDate(dateFrom.value),
        formatDate(dateTo.value),
      ),
      advertiserApi.getBreakdown(
        'TEAM',
        teamSlug,
        campaignId,
        formatDate(dateFrom.value),
        formatDate(dateTo.value),
      ),
    ])
    performance.value = perfRes.data
    creatives.value = creativeRes.data
    breakdown.value = breakdownRes.data
  }
  catch {
    // エラー時は空のまま
  }
  finally {
    loading.value = false
  }
}

async function handleExportCsv() {
  exportingCsv.value = true
  try {
    const blob = await advertiserApi.exportCampaignCsv(
      'TEAM',
      teamSlug,
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
  }
  catch {
    // ignore
  }
  finally {
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
      :back-to="`/teams/${teamSlug}/advertiser`"
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

    <template v-else-if="performance">
      <AdvertiserCampaignMetricsCards :performance="performance" />
      <AdvertiserCampaignDataTables
        :points="performance.points"
        :creatives="creatives"
        :breakdown="breakdown"
      />
    </template>

    <div v-else class="py-20 text-center">
      <i class="pi pi-chart-bar mb-4 text-6xl text-surface-400" />
      <p class="text-surface-500">{{ t('advertising.teams_page.campaign_detail.load_failed') }}</p>
      <NuxtLink :to="`/teams/${teamSlug}/advertiser`">
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
