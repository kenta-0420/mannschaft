<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = Number(route.params.id)
const { getSummary } = useBudgetApi()
const { showError } = useNotification()
const summary = ref<Record<string, unknown> | null>(null)
const loading = ref(false)
async function load() {
  loading.value = true
  try {
    const res = await getSummary(orgId, 'ORGANIZATION')
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
          <p class="text-sm text-surface-500">予算額</p>
          <p class="text-2xl font-bold text-primary">
            ¥{{ summary.budgetAmount?.toLocaleString() ?? 0 }}
          </p>
        </SectionCard>
        <SectionCard>
          <p class="text-sm text-surface-500">支出額</p>
          <p class="text-2xl font-bold text-red-600">
            ¥{{ summary.spentAmount?.toLocaleString() ?? 0 }}
          </p>
        </SectionCard>
        <SectionCard>
          <p class="text-sm text-surface-500">残額</p>
          <p class="text-2xl font-bold text-green-600">
            ¥{{ summary.remainingAmount?.toLocaleString() ?? 0 }}
          </p>
        </SectionCard>
      </div>
    </template>
    <DashboardEmptyState v-else icon="pi pi-wallet" message="予算データがありません" />
  </div>
</template>
