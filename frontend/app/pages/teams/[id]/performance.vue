<script setup lang="ts">
import type { PerformanceStats } from '~/types/performance'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)

const { getTeamStats } = usePerformanceApi()
const { showError } = useNotification()

const stats = ref<PerformanceStats[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getTeamStats(teamId)
    stats.value = res.data
  } catch {
    showError('パフォーマンスデータの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <div>
    <div class="mb-4">
      <PageHeader title="パフォーマンス" />
    </div>

    <PageLoading v-if="loading" size="40px" />

    <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <SectionCard
        v-for="stat in stats"
        :key="stat.metricId"
        :title="stat.metricName"
      >
        <div class="flex items-end gap-4">
          <div>
            <p class="text-2xl font-bold text-primary">{{ stat.teamAverage.toFixed(1) }}</p>
            <p class="text-xs text-surface-400">
              チーム平均{{ stat.unit ? ` (${stat.unit})` : '' }}
            </p>
          </div>
          <div>
            <p class="text-lg font-semibold text-green-600">{{ stat.teamBest.toFixed(1) }}</p>
            <p class="text-xs text-surface-400">最高記録</p>
          </div>
        </div>
        <p class="mt-2 text-xs text-surface-400">{{ stat.totalRecords }}件のデータ</p>
      </SectionCard>
    </div>

    <DashboardEmptyState
      v-if="!loading && stats.length === 0"
      icon="pi pi-chart-line"
      message="パフォーマンスデータがありません"
    />
  </div>
</template>
