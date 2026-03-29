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

// 日付範囲（デフォルト: 過去30日）
const now = new Date()
const thirtyDaysAgo = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000)
const dateFrom = ref<Date>(thirtyDaysAgo)
const dateTo = ref<Date>(now)

function formatDate(d: Date): string {
  return d.toISOString().slice(0, 10)
}

const statusSeverityMap: Record<string, string> = {
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
    <!-- ヘッダー -->
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
            :severity="(statusSeverityMap[performance.status] as any) ?? 'secondary'"
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
      <!-- サマリーカード -->
      <div class="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-5">
        <div
          class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
        >
          <p class="text-sm text-surface-500">インプレッション</p>
          <p class="text-2xl font-bold">
            {{ performance.summary.totalImpressions.toLocaleString() }}
          </p>
        </div>
        <div
          class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
        >
          <p class="text-sm text-surface-500">クリック</p>
          <p class="text-2xl font-bold">{{ performance.summary.totalClicks.toLocaleString() }}</p>
        </div>
        <div
          class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
        >
          <p class="text-sm text-surface-500">CTR</p>
          <p class="text-2xl font-bold">{{ performance.summary.avgCtr }}%</p>
        </div>
        <div
          class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
        >
          <p class="text-sm text-surface-500">広告費</p>
          <p class="text-2xl font-bold">¥{{ performance.summary.totalCost.toLocaleString() }}</p>
        </div>
        <div
          class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
        >
          <p class="text-sm text-surface-500">
            {{ performance.pricingModel === 'CPM' ? '平均CPM' : '平均CPC' }}
          </p>
          <p class="text-2xl font-bold">
            ¥{{
              (performance.pricingModel === 'CPM'
                ? performance.summary.avgCpm
                : performance.summary.avgCpc
              )?.toLocaleString() ?? '-'
            }}
          </p>
        </div>
      </div>

      <!-- コンバージョン情報 -->
      <div
        v-if="performance.summary.conversions != null"
        class="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-3"
      >
        <div
          class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
        >
          <p class="text-sm text-surface-500">コンバージョン</p>
          <p class="text-2xl font-bold">{{ performance.summary.conversions?.toLocaleString() }}</p>
        </div>
        <div
          class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
        >
          <p class="text-sm text-surface-500">CVR</p>
          <p class="text-2xl font-bold">{{ performance.summary.conversionRate ?? '-' }}%</p>
        </div>
        <div
          class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
        >
          <p class="text-sm text-surface-500">CPA</p>
          <p class="text-2xl font-bold">
            ¥{{ performance.summary.costPerConversion?.toLocaleString() ?? '-' }}
          </p>
        </div>
      </div>

      <!-- ベンチマーク -->
      <div
        v-if="performance.benchmark"
        class="mb-6 rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
      >
        <h3 class="mb-3 font-semibold">ベンチマーク比較</h3>
        <div class="grid grid-cols-2 gap-4 lg:grid-cols-4">
          <div>
            <p class="text-sm text-surface-500">プラットフォーム平均CTR</p>
            <p class="text-lg font-bold">{{ performance.benchmark.platformAvgCtr ?? '-' }}%</p>
          </div>
          <div>
            <p class="text-sm text-surface-500">あなたのCTRパーセンタイル</p>
            <p class="text-lg font-bold">{{ performance.benchmark.yourCtrPercentile ?? '-' }}%</p>
          </div>
          <div>
            <p class="text-sm text-surface-500">同テンプレ平均CTR</p>
            <p class="text-lg font-bold">{{ performance.benchmark.sameTemplateAvgCtr ?? '-' }}%</p>
          </div>
          <div>
            <p class="text-sm text-surface-500">同テンプレ平均CPC</p>
            <p class="text-lg font-bold">
              ¥{{ performance.benchmark.sameTemplateAvgCpc?.toLocaleString() ?? '-' }}
            </p>
          </div>
        </div>
      </div>

      <!-- 日次推移テーブル -->
      <div
        v-if="performance.points.length"
        class="mb-6 rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
      >
        <h3 class="mb-3 font-semibold">日次推移</h3>
        <DataTable :value="performance.points" :rows="10" paginator striped-rows>
          <Column field="period" header="期間" />
          <Column field="impressions" header="imp" class="text-right">
            <template #body="{ data }">{{ data.impressions.toLocaleString() }}</template>
          </Column>
          <Column field="clicks" header="click" class="text-right">
            <template #body="{ data }">{{ data.clicks.toLocaleString() }}</template>
          </Column>
          <Column field="ctr" header="CTR">
            <template #body="{ data }">{{ data.ctr }}%</template>
          </Column>
          <Column field="cost" header="費用" class="text-right">
            <template #body="{ data }">¥{{ data.cost.toLocaleString() }}</template>
          </Column>
        </DataTable>
      </div>

      <!-- クリエイティブ比較 -->
      <div
        v-if="creatives && creatives.creatives.length"
        class="mb-6 rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
      >
        <h3 class="mb-3 font-semibold">クリエイティブ比較</h3>
        <Message v-if="creatives.winner" severity="success" :closable="false" class="mb-3">
          勝者: Ad #{{ creatives.winner.adId }} — {{ creatives.winner.reason }}
        </Message>
        <DataTable :value="creatives.creatives" striped-rows>
          <Column field="title" header="クリエイティブ" />
          <Column field="impressions" header="imp" class="text-right">
            <template #body="{ data }">{{ data.impressions.toLocaleString() }}</template>
          </Column>
          <Column field="clicks" header="click" class="text-right">
            <template #body="{ data }">{{ data.clicks.toLocaleString() }}</template>
          </Column>
          <Column field="ctr" header="CTR">
            <template #body="{ data }">{{ data.ctr }}%</template>
          </Column>
          <Column field="cost" header="費用" class="text-right">
            <template #body="{ data }">¥{{ data.cost.toLocaleString() }}</template>
          </Column>
        </DataTable>
      </div>

      <!-- ブレイクダウン -->
      <div
        v-if="breakdown && breakdown.items.length"
        class="mb-6 rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
      >
        <h3 class="mb-3 font-semibold">ブレイクダウン（{{ breakdown.breakdownBy }}）</h3>
        <DataTable :value="breakdown.items" striped-rows>
          <Column header="セグメント">
            <template #body="{ data }">{{ data.prefecture || data.template || '-' }}</template>
          </Column>
          <Column field="impressions" header="imp" class="text-right">
            <template #body="{ data }">{{ data.impressions.toLocaleString() }}</template>
          </Column>
          <Column field="clicks" header="click" class="text-right">
            <template #body="{ data }">{{ data.clicks.toLocaleString() }}</template>
          </Column>
          <Column field="ctr" header="CTR">
            <template #body="{ data }">{{ data.ctr }}%</template>
          </Column>
          <Column field="cost" header="費用" class="text-right">
            <template #body="{ data }">¥{{ data.cost.toLocaleString() }}</template>
          </Column>
        </DataTable>
      </div>
    </template>

    <!-- データなし -->
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
