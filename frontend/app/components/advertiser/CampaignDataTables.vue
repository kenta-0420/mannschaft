<script setup lang="ts">
import type {
  CampaignPerformanceResponse,
  CreativeComparisonResponse,
  BreakdownResponse,
} from '~/types/advertiser'

defineProps<{
  points: CampaignPerformanceResponse['points']
  creatives: CreativeComparisonResponse | null
  breakdown: BreakdownResponse | null
}>()

const sectionClass =
  'mb-6 rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800'
</script>

<template>
  <div>
    <div v-if="points.length" :class="sectionClass">
      <h3 class="mb-3 font-semibold">日次推移</h3>
      <DataTable :value="points" :rows="10" paginator striped-rows>
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

    <div v-if="creatives && creatives.creatives.length" :class="sectionClass">
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

    <div v-if="breakdown && breakdown.items.length" :class="sectionClass">
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
  </div>
</template>
