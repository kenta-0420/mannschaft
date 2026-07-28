<script setup lang="ts">
// F09.19.6 チームスコープ 料金シミュレーターページ。
// 組織版 (pages/organizations/[slug]/advertiser/rate-simulator.vue) を team scope で読み替えたもの。
// rate-simulator / rate-cards はスコープ非依存 API（§9）のため useAdvertiserApi 側のシグネチャは変更しない。

import type { PricingModel, RateSimulatorResponse } from '~/types/advertiser'

definePageMeta({ layout: 'team', middleware: 'auth' })
const route = useRoute()
const { t } = useI18n()
const teamSlug = String(route.params.slug)
const advertiserApi = useAdvertiserApi()

const form = ref({
  prefecture: '',
  template: '',
  pricingModel: 'CPM' as PricingModel,
  impressions: 100000,
  clicks: 0,
  days: 30,
})
const result = ref<RateSimulatorResponse | null>(null)
const loading = ref(false)

const pricingOptions = computed(() => [
  { label: t('advertising.teams_page.rate_simulator.pricing_model_cpm'), value: 'CPM' },
  { label: t('advertising.teams_page.rate_simulator.pricing_model_cpc'), value: 'CPC' },
])

async function simulate() {
  loading.value = true
  result.value = null
  try {
    const params: Record<string, string | number> = { pricingModel: form.value.pricingModel, days: form.value.days }
    if (form.value.prefecture) params.prefecture = form.value.prefecture
    if (form.value.template) params.template = form.value.template
    if (form.value.pricingModel === 'CPM') params.impressions = form.value.impressions
    else params.clicks = form.value.clicks
    const res = await advertiserApi.simulateRate(params as Parameters<typeof advertiserApi.simulateRate>[0])
    result.value = res.data
  }
  catch { /* handled by global */ }
  finally { loading.value = false }
}
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <PageHeader :title="t('advertising.teams_page.rate_simulator.title')" :back-to="`/teams/${teamSlug}/advertiser`" />

    <SectionCard class="mb-6">
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('advertising.teams_page.rate_simulator.pricing_model_label') }}</label>
          <Select v-model="form.pricingModel" :options="pricingOptions" option-label="label" option-value="value" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('advertising.teams_page.rate_simulator.days_label') }}</label>
          <InputNumber v-model="form.days" :min="1" :max="365" class="w-full" />
        </div>
        <div v-if="form.pricingModel === 'CPM'">
          <label class="mb-1 block text-sm font-medium">{{ t('advertising.teams_page.rate_simulator.impressions_label') }}</label>
          <InputNumber v-model="form.impressions" :min="1000" :step="10000" class="w-full" />
        </div>
        <div v-else>
          <label class="mb-1 block text-sm font-medium">{{ t('advertising.teams_page.rate_simulator.clicks_label') }}</label>
          <InputNumber v-model="form.clicks" :min="100" :step="1000" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('advertising.teams_page.rate_simulator.prefecture_label') }}</label>
          <InputText v-model="form.prefecture" class="w-full" :placeholder="t('advertising.teams_page.rate_simulator.prefecture_placeholder')" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('advertising.teams_page.rate_simulator.template_label') }}</label>
          <InputText v-model="form.template" class="w-full" :placeholder="t('advertising.teams_page.rate_simulator.template_placeholder')" />
        </div>
      </div>
      <Button :label="t('advertising.teams_page.rate_simulator.run_button')" icon="pi pi-calculator" :loading="loading" class="mt-4 w-full" @click="simulate" />
    </SectionCard>

    <!-- 結果 -->
    <div v-if="result" class="space-y-4">
      <SectionCard :title="t('advertising.teams_page.rate_simulator.result_title')">
        <div class="grid grid-cols-2 gap-4 sm:grid-cols-3">
          <div>
            <p class="text-sm text-surface-500">{{ t('advertising.teams_page.rate_simulator.unit_price_label') }}</p>
            <p class="text-lg font-bold">{{ result.rateCard.unitPrice }} {{ result.rateCard.unitLabel }}</p>
          </div>
          <div>
            <p class="text-sm text-surface-500">{{ t('advertising.teams_page.rate_simulator.total_cost_label') }}</p>
            <p class="text-lg font-bold">¥{{ result.estimate.totalCost.toLocaleString() }}</p>
          </div>
          <div>
            <p class="text-sm text-surface-500">{{ t('advertising.teams_page.rate_simulator.total_with_tax_label') }}</p>
            <p class="text-2xl font-bold text-primary">¥{{ result.estimate.totalWithTax.toLocaleString() }}</p>
          </div>
          <div>
            <p class="text-sm text-surface-500">{{ t('advertising.teams_page.rate_simulator.tax_amount_label') }}</p>
            <p class="font-semibold">¥{{ result.estimate.taxAmount.toLocaleString() }}</p>
          </div>
          <div>
            <p class="text-sm text-surface-500">{{ t('advertising.teams_page.rate_simulator.daily_cost_label') }}</p>
            <p class="font-semibold">¥{{ result.estimate.dailyCost.toLocaleString() }}</p>
          </div>
          <div>
            <p class="text-sm text-surface-500">{{ t('advertising.teams_page.rate_simulator.min_daily_budget_label') }}</p>
            <p class="font-semibold">¥{{ result.rateCard.minDailyBudget.toLocaleString() }}</p>
          </div>
        </div>
      </SectionCard>

      <SectionCard v-if="result.comparison.length > 0" :title="t('advertising.teams_page.rate_simulator.comparison_title')">
        <DataTable :value="result.comparison" striped-rows>
          <Column field="label" :header="t('advertising.teams_page.rate_simulator.comparison_column_label')" />
          <Column field="unitPrice" :header="t('advertising.teams_page.rate_simulator.comparison_column_unit_price')">
            <template #body="{ data }">{{ data.unitPrice }}</template>
          </Column>
          <Column field="totalCost" :header="t('advertising.teams_page.rate_simulator.comparison_column_total_cost')">
            <template #body="{ data }">¥{{ data.totalCost.toLocaleString() }}</template>
          </Column>
        </DataTable>
      </SectionCard>
    </div>
  </div>
</template>
