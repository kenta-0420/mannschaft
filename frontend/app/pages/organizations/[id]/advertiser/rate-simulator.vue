<script setup lang="ts">
import type { PricingModel, RateSimulatorResponse } from '~/types/advertiser'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
void route.params.id // organizationId はシミュレーターでは未使用（認証のみ）
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

const pricingOptions = [
  { label: 'CPM（インプレッション課金）', value: 'CPM' },
  { label: 'CPC（クリック課金）', value: 'CPC' },
]

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
    <h1 class="mb-6 text-2xl font-bold">料金シミュレーター</h1>

    <div class="mb-6 rounded-xl border border-surface-300 bg-surface-0 p-6 dark:border-surface-600 dark:bg-surface-800">
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div>
          <label class="mb-1 block text-sm font-medium">課金モデル</label>
          <Select v-model="form.pricingModel" :options="pricingOptions" option-label="label" option-value="value" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">掲載日数</label>
          <InputNumber v-model="form.days" :min="1" :max="365" class="w-full" />
        </div>
        <div v-if="form.pricingModel === 'CPM'">
          <label class="mb-1 block text-sm font-medium">インプレッション数</label>
          <InputNumber v-model="form.impressions" :min="1000" :step="10000" class="w-full" />
        </div>
        <div v-else>
          <label class="mb-1 block text-sm font-medium">クリック数</label>
          <InputNumber v-model="form.clicks" :min="100" :step="1000" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">都道府県（任意）</label>
          <InputText v-model="form.prefecture" class="w-full" placeholder="例: 東京都" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">テンプレート（任意）</label>
          <InputText v-model="form.template" class="w-full" placeholder="例: SPORTS" />
        </div>
      </div>
      <Button label="シミュレーション実行" icon="pi pi-calculator" :loading="loading" class="mt-4 w-full" @click="simulate" />
    </div>

    <!-- 結果 -->
    <div v-if="result" class="space-y-4">
      <div class="rounded-xl border border-surface-300 bg-surface-0 p-6 dark:border-surface-600 dark:bg-surface-800">
        <h3 class="mb-4 font-semibold">見積もり結果</h3>
        <div class="grid grid-cols-2 gap-4 sm:grid-cols-3">
          <div>
            <p class="text-sm text-surface-500">単価</p>
            <p class="text-lg font-bold">{{ result.rateCard.unitPrice }} {{ result.rateCard.unitLabel }}</p>
          </div>
          <div>
            <p class="text-sm text-surface-500">税抜合計</p>
            <p class="text-lg font-bold">¥{{ result.estimate.totalCost.toLocaleString() }}</p>
          </div>
          <div>
            <p class="text-sm text-surface-500">税込合計</p>
            <p class="text-2xl font-bold text-primary">¥{{ result.estimate.totalWithTax.toLocaleString() }}</p>
          </div>
          <div>
            <p class="text-sm text-surface-500">消費税</p>
            <p class="font-semibold">¥{{ result.estimate.taxAmount.toLocaleString() }}</p>
          </div>
          <div>
            <p class="text-sm text-surface-500">日次費用</p>
            <p class="font-semibold">¥{{ result.estimate.dailyCost.toLocaleString() }}</p>
          </div>
          <div>
            <p class="text-sm text-surface-500">最低日予算</p>
            <p class="font-semibold">¥{{ result.rateCard.minDailyBudget.toLocaleString() }}</p>
          </div>
        </div>
      </div>

      <div v-if="result.comparison.length > 0" class="rounded-xl border border-surface-300 bg-surface-0 p-6 dark:border-surface-600 dark:bg-surface-800">
        <h3 class="mb-4 font-semibold">他の料金との比較</h3>
        <DataTable :value="result.comparison" striped-rows>
          <Column field="label" header="条件" />
          <Column field="unitPrice" header="単価">
            <template #body="{ data }">{{ data.unitPrice }}</template>
          </Column>
          <Column field="totalCost" header="合計費用">
            <template #body="{ data }">¥{{ data.totalCost.toLocaleString() }}</template>
          </Column>
        </DataTable>
      </div>
    </div>
  </div>
</template>
