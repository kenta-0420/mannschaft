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
      <h1 class="text-2xl font-bold">パフォーマンス</h1>
    </div>

    <PageLoading v-if="loading" size="40px" />

    <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <div
        v-for="stat in stats"
        :key="stat.metricId"
        class="rounded-xl border border-surface-200 bg-surface-0 p-4"
      >
        <h3 class="mb-2 text-sm font-semibold">{{ stat.metricName }}</h3>
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
      </div>
    </div>

    <div v-if="!loading && stats.length === 0" class="py-12 text-center">
      <i class="pi pi-chart-line mb-3 text-4xl text-surface-300" />
      <p class="text-surface-400">パフォーマンスデータがありません</p>
    </div>
  </div>
</template>
