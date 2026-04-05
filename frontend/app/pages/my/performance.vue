<script setup lang="ts">
import type { MemberPerformance } from '~/types/performance'

definePageMeta({ middleware: 'auth' })

const { getMyPerformance } = usePerformanceApi()
const notification = useNotification()

const data = ref<MemberPerformance | null>(null)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getMyPerformance()
    data.value = res.data
  } catch {
    notification.error('パフォーマンスデータの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function getTrendIcon(trend: 'UP' | 'DOWN' | 'STABLE'): string {
  switch (trend) {
    case 'UP': return 'pi pi-arrow-up'
    case 'DOWN': return 'pi pi-arrow-down'
    default: return 'pi pi-minus'
  }
}

function getTrendClass(trend: 'UP' | 'DOWN' | 'STABLE'): string {
  switch (trend) {
    case 'UP': return 'text-green-500'
    case 'DOWN': return 'text-red-500'
    default: return 'text-surface-400'
  }
}

onMounted(() => load())
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <h1 class="mb-6 text-2xl font-bold">マイパフォーマンス</h1>
    <PageLoading v-if="loading" size="40px" />
    <template v-else-if="data">
      <p class="mb-4 text-sm text-surface-400">{{ data.displayName }}</p>
      <div v-if="data.metrics.length > 0" class="grid gap-4 md:grid-cols-3">
        <div
          v-for="m in data.metrics"
          :key="m.metricId"
          class="rounded-xl border border-surface-300 bg-surface-0 p-4 text-center"
        >
          <p class="mb-1 text-sm text-surface-500">{{ m.metricName }}</p>
          <p class="text-2xl font-bold text-primary">{{ m.value }}</p>
          <div class="mt-2 flex items-center justify-center gap-2 text-xs">
            <i :class="[getTrendIcon(m.trend), getTrendClass(m.trend)]" />
            <span class="text-surface-400">{{ m.percentile }}%ile</span>
            <span class="text-surface-400">ランク {{ m.rank }}</span>
          </div>
        </div>
      </div>
      <div v-else class="py-8 text-center">
        <i class="pi pi-chart-bar mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">記録されたメトリクスがありません</p>
      </div>
    </template>
    <div v-else class="py-12 text-center">
      <i class="pi pi-chart-bar mb-3 text-4xl text-surface-300" />
      <p class="text-surface-400">パフォーマンスデータがありません</p>
    </div>
  </div>
</template>
