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
</script>

<template>
  <div>
    <SectionCard v-if="points.length" title="日次推移" class="mb-6">
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
    </SectionCard>

    <SectionCard v-if="creatives && creatives.creatives.length" title="クリエイティブ比較" class="mb-6">
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
    </SectionCard>

    <SectionCard v-if="breakdown && breakdown.items.length" :title="`ブレイクダウン（${breakdown.breakdownBy}）`" class="mb-6">
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
    </SectionCard>
  </div>
</template>
