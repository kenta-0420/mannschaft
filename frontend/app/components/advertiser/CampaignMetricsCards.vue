<script setup lang="ts">
import type { CampaignPerformanceResponse } from '~/types/advertiser'

defineProps<{
  performance: CampaignPerformanceResponse
}>()
</script>

<template>
  <div>
    <div class="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-5">
      <SectionCard>
        <p class="text-sm text-surface-500">インプレッション</p>
        <p class="text-2xl font-bold">
          {{ performance.summary.totalImpressions.toLocaleString() }}
        </p>
      </SectionCard>
      <SectionCard>
        <p class="text-sm text-surface-500">クリック</p>
        <p class="text-2xl font-bold">{{ performance.summary.totalClicks.toLocaleString() }}</p>
      </SectionCard>
      <SectionCard>
        <p class="text-sm text-surface-500">CTR</p>
        <p class="text-2xl font-bold">{{ performance.summary.avgCtr }}%</p>
      </SectionCard>
      <SectionCard>
        <p class="text-sm text-surface-500">広告費</p>
        <p class="text-2xl font-bold">¥{{ performance.summary.totalCost.toLocaleString() }}</p>
      </SectionCard>
      <SectionCard>
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
      </SectionCard>
    </div>

    <div
      v-if="performance.summary.conversions != null"
      class="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-3"
    >
      <SectionCard>
        <p class="text-sm text-surface-500">コンバージョン</p>
        <p class="text-2xl font-bold">{{ performance.summary.conversions?.toLocaleString() }}</p>
      </SectionCard>
      <SectionCard>
        <p class="text-sm text-surface-500">CVR</p>
        <p class="text-2xl font-bold">{{ performance.summary.conversionRate ?? '-' }}%</p>
      </SectionCard>
      <SectionCard>
        <p class="text-sm text-surface-500">CPA</p>
        <p class="text-2xl font-bold">
          ¥{{ performance.summary.costPerConversion?.toLocaleString() ?? '-' }}
        </p>
      </SectionCard>
    </div>

    <SectionCard
      v-if="performance.benchmark"
      title="ベンチマーク比較"
      class="mb-6"
    >
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
    </SectionCard>
  </div>
</template>
