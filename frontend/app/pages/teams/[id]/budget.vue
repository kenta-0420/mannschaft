<script setup lang="ts">
import type { FiscalYearResponse, BudgetSummary } from '~/types/budget'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)

const { getFiscalYears, getSummary } = useBudgetApi()
const { showError } = useNotification()

const fiscalYears = ref<FiscalYearResponse[]>([])
const selectedFy = ref<FiscalYearResponse | null>(null)
const summary = ref<BudgetSummary | null>(null)
const loading = ref(false)

async function load() {
  try {
    const res = await getFiscalYears('team', teamId)
    fiscalYears.value = res.data
    if (res.data.length > 0) selectFy(res.data[0])
  } catch {
    showError('予算情報の取得に失敗しました')
  }
}

async function selectFy(fy: FiscalYearResponse) {
  selectedFy.value = fy
  loading.value = true
  try {
    const res = await getSummary('team', teamId, fy.id)
    summary.value = res.data
  } catch {
    showError('予算サマリーの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-2xl font-bold">予算・会計</h1>
      <Button label="年度を追加" icon="pi pi-plus" />
    </div>
    <div v-if="fiscalYears.length > 0" class="mb-4">
      <SelectButton
        :model-value="selectedFy?.id"
        :options="fiscalYears.map((fy) => ({ label: fy.name, value: fy.id }))"
        option-label="label"
        option-value="value"
        @update:model-value="
          (id: number) => {
            const fy = fiscalYears.find((f) => f.id === id)
            if (fy) selectFy(fy)
          }
        "
      />
    </div>
    <PageLoading v-if="loading" size="40px" />
    <div v-else-if="summary" class="grid gap-4 sm:grid-cols-3">
      <div class="rounded-xl border border-surface-300 bg-surface-0 p-4">
        <p class="text-xs text-surface-400">収入</p>
        <p class="text-2xl font-bold text-green-600">¥{{ summary.totalIncome.toLocaleString() }}</p>
      </div>
      <div class="rounded-xl border border-surface-300 bg-surface-0 p-4">
        <p class="text-xs text-surface-400">支出</p>
        <p class="text-2xl font-bold text-red-500">¥{{ summary.totalExpense.toLocaleString() }}</p>
      </div>
      <div class="rounded-xl border border-surface-300 bg-surface-0 p-4">
        <p class="text-xs text-surface-400">残高</p>
        <p
          class="text-2xl font-bold"
          :class="summary.balance >= 0 ? 'text-primary' : 'text-red-500'"
        >
          ¥{{ summary.balance.toLocaleString() }}
        </p>
      </div>
    </div>
    <div v-if="!loading && summary" class="mt-4 flex flex-col gap-2">
      <div
        v-for="cat in summary.byCategory"
        :key="cat.categoryId"
        class="flex items-center justify-between rounded-lg border border-surface-100 px-4 py-2"
      >
        <span class="text-sm">{{ cat.categoryName }}</span>
        <div class="flex items-center gap-4 text-sm">
          <span class="text-surface-400">予算 ¥{{ cat.allocated.toLocaleString() }}</span>
          <span :class="cat.categoryType === 'INCOME' ? 'text-green-600' : 'text-red-500'"
            >実績 ¥{{ cat.actual.toLocaleString() }}</span
          >
          <span
            class="text-xs"
            :class="cat.burnPercent > 100 ? 'text-red-500 font-bold' : 'text-surface-400'"
            >{{ cat.burnPercent }}%</span
          >
        </div>
      </div>
    </div>
  </div>
</template>
