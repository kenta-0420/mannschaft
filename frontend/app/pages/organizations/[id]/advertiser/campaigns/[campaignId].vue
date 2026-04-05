<script setup lang="ts">
import type {
  CampaignPerformanceResponse,
  CreativeComparisonResponse,
  BreakdownResponse,
} from '~/types/advertiser'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgId = Number(route.params.id)
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
        campaignId,
        orgId,
        formatDate(dateFrom.value),
        formatDate(dateTo.value),
      ),
      advertiserApi.getCreativeComparison(
        campaignId,
        orgId,
        formatDate(dateFrom.value),
        formatDate(dateTo.value),
      ),
      advertiserApi.getBreakdown(
        campaignId,
        orgId,
        formatDate(dateFrom.value),
        formatDate(dateTo.value),
      ),
    ])
    performance.value = perfRes.data
    creatives.value = creativeRes.data
    breakdown.value = breakdownRes.data
  } catch {
    // エラー時は空のまま
  } finally {
    loading.value = false
  }
}

async function handleExportCsv() {
  exportingCsv.value = true
  try {
    const blob = await advertiserApi.exportCampaignCsv(
      campaignId,
      orgId,
      formatDate(dateFrom.value),
      formatDate(dateTo.value),
    )
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `campaign-${campaignId}-report.csv`
    a.click()
    URL.revokeObjectURL(url)
  } catch {
    // ignore
  } finally {
    exportingCsv.value = false
  }
}

onMounted(load)
watch([dateFrom, dateTo], load)
</script>

<template>
  <div>
    <div class="mb-6 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
      <div class="flex items-center gap-3">
        <NuxtLink :to="`/organizations/${orgId}/advertiser`">
          <Button icon="pi pi-arrow-left" text rounded />
        </NuxtLink>
        <div>
          <h1 class="text-2xl font-bold">
            {{ performance?.campaignName ?? 'キャンペーン詳細' }}
          </h1>
          <Tag
            v-if="performance"
            :value="performance.status"
            :severity="statusSeverityMap[performance.status] ?? 'secondary'"
            class="mt-1"
          />
        </div>
      </div>
      <div class="flex items-center gap-2">
        <DatePicker v-model="dateFrom" date-format="yy-mm-dd" placeholder="開始日" class="w-36" />
        <span class="text-surface-400">〜</span>
        <DatePicker v-model="dateTo" date-format="yy-mm-dd" placeholder="終了日" class="w-36" />
        <Button
          icon="pi pi-download"
          label="CSV"
          severity="secondary"
          size="small"
          :loading="exportingCsv"
          @click="handleExportCsv"
        />
      </div>
    </div>

    <ProgressSpinner v-if="loading" class="flex justify-center py-20" />

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
      <p class="text-surface-500">キャンペーンデータを取得できませんでした。</p>
      <NuxtLink :to="`/organizations/${orgId}/advertiser`">
        <Button
          label="ダッシュボードに戻る"
          icon="pi pi-arrow-left"
          severity="secondary"
          class="mt-4"
        />
      </NuxtLink>
    </div>
  </div>
</template>
