<script setup lang="ts">
import type { MemberPerformance } from '~/types/performance'

definePageMeta({ middleware: 'auth' })

const { getMyPerformance } = usePerformanceApi()
const { showError } = useNotification()

const data = ref<MemberPerformance | null>(null)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getMyPerformance()
    data.value = res.data
  } catch {
    showError('パフォーマンスデータの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <PageHeader title="マイパフォーマンス" />
    <PageLoading v-if="loading" size="40px" />
    <template v-else-if="data">
      <div class="grid gap-4 md:grid-cols-3">
        <SectionCard
          v-for="metric in data.metrics"
          :key="metric.metricId"
          class="text-center"
        >
          <p class="text-sm text-surface-500">{{ metric.metricName }}</p>
          <p class="text-2xl font-bold text-primary">{{ metric.value }}</p>
        </SectionCard>
      </div>
    </template>
    <DashboardEmptyState v-else icon="pi-chart-bar" message="パフォーマンスデータがありません" />
  </div>
</template>
