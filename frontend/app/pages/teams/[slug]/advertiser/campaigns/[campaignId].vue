<script setup lang="ts">
// F09.19.6 チームスコープ キャンペーン詳細（パフォーマンス）。
// 組織版 (pages/organizations/[slug]/advertiser/campaigns/[campaignId].vue) を金型に、
// team scope で実装済みの GET /api/v1/teams/{teamId}/advertiser/campaigns/{campaignId}/performance
// （F09.19.5 AC-5.2 / TeamAdvertiserDashboardController）のみを利用する。
//
// クリエイティブ比較（/creatives）・ブレイクダウン（/breakdown）・CSV エクスポート（/export）は、
// team scope 向けのバックエンド実装が存在しない
// （CampaignPerformanceService に ScopeType 引数オーバーロードがあるのは getPerformance のみで、
// getCreativeComparison / getBreakdown / CsvExportService は ScopeType.ORGANIZATION 固定のまま）
// のため、本ページでは提供しない（AdvertiserCampaignDataTables には null を渡し非表示化する）。

import type { CampaignPerformanceResponse } from '~/types/advertiser'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const { t } = useI18n()
const teamSlug = String(route.params.slug)
const campaignId = Number(route.params.campaignId)
const advertiserApi = useAdvertiserApi()

const loading = ref(true)
const performance = ref<CampaignPerformanceResponse | null>(null)

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
    const perfRes = await advertiserApi.getCampaignPerformance(
      'TEAM',
      teamSlug,
      campaignId,
      formatDate(dateFrom.value),
      formatDate(dateTo.value),
    )
    performance.value = perfRes.data
  } catch {
    // エラー時は空のまま
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch([dateFrom, dateTo], load)
</script>

<template>
  <div>
    <div class="mb-6 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
      <div class="flex items-center gap-3">
        <BackButton :to="`/teams/${teamSlug}/advertiser`" />
        <div>
          <h1 class="text-2xl font-bold">
            {{ performance?.campaignName ?? t('advertising.teams_page.campaign_detail.fallback_title') }}
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
      </div>
    </div>

    <Message severity="info" :closable="false" class="mb-6">
      {{ t('advertising.teams_page.campaign_detail.extra_ops_notice') }}
    </Message>

    <div v-if="loading" class="flex justify-center py-20"><LoadingBounce /></div>

    <template v-else-if="performance">
      <AdvertiserCampaignMetricsCards :performance="performance" />
      <AdvertiserCampaignDataTables
        :points="performance.points"
        :creatives="null"
        :breakdown="null"
      />
    </template>

    <div v-else class="py-20 text-center">
      <i class="pi pi-chart-bar mb-4 text-6xl text-surface-400" />
      <p class="text-surface-500">
        {{ t('advertising.teams_page.campaign_detail.empty_message') }}
      </p>
      <NuxtLink :to="`/teams/${teamSlug}/advertiser`">
        <Button
          :label="t('advertising.teams_page.campaign_detail.back_label')"
          icon="pi pi-arrow-left"
          severity="secondary"
          class="mt-4"
        />
      </NuxtLink>
    </div>
  </div>
</template>
