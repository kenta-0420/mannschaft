<script setup lang="ts">
import type { BudgetSummary } from '~/types/budget'
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = Number(route.params.id)
const { getSummary, getFiscalYears } = useBudgetApi()
const { showError } = useNotification()
const summary = ref<BudgetSummary | null>(null)
const loading = ref(false)
async function load() {
  loading.value = true
  try {
    // まず最新の会計年度を取得してから集計を取得する
    const fyRes = await getFiscalYears('organization', orgId)
    const latestFY = fyRes.data[0]
    if (!latestFY) return
    const res = await getSummary('organization', orgId, latestFY.id)
    summary.value = res.data
  } catch {
    showError('予算情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
}
onMounted(() => load())
</script>
<template>
  <div>
    <PageHeader title="予算・会計" />
    <PageLoading v-if="loading" size="40px" />
    <template v-else-if="summary">
      <div class="mb-6 grid gap-4 md:grid-cols-3">
        <SectionCard>
          <p class="text-sm text-surface-500">収入合計</p>
          <p class="text-2xl font-bold text-primary">
            ¥{{ summary.totalIncome.toLocaleString() }}
          </p>
        </SectionCard>
        <SectionCard>
          <p class="text-sm text-surface-500">支出合計</p>
          <p class="text-2xl font-bold text-red-600">
            ¥{{ summary.totalExpense.toLocaleString() }}
          </p>
        </SectionCard>
        <SectionCard>
          <p class="text-sm text-surface-500">残高</p>
          <p class="text-2xl font-bold text-green-600">
            ¥{{ summary.balance.toLocaleString() }}
          </p>
        </SectionCard>
      </div>
    </template>
    <DashboardEmptyState v-else icon="pi pi-wallet" message="予算データがありません" />
  </div>
</template>
