<script setup lang="ts">
import {
  Chart,
  BarController,
  CategoryScale,
  LinearScale,
  BarElement,
  Tooltip,
  Legend,
} from 'chart.js'

Chart.register(BarController, CategoryScale, LinearScale, BarElement, Tooltip, Legend)

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
}>()

const { getStats } = useActivityApi()
const { showError } = useNotification()
const { t } = useI18n()

// デフォルト: 過去6ヶ月〜今日
const today = new Date()
const sixMonthsAgo = new Date(today)
sixMonthsAgo.setMonth(sixMonthsAgo.getMonth() - 6)

const periodStart = ref<Date>(sixMonthsAgo)
const periodEnd = ref<Date>(today)

const stats = ref<{
  totalActivities: number
  totalParticipants: number
  averageParticipants: number
  monthlyBreakdown: Array<{ month: string; count: number }>
} | null>(null)

const loading = ref(false)

const chartRef = ref<HTMLCanvasElement | null>(null)
let chartInstance: Chart | null = null

async function fetchStats() {
  loading.value = true
  try {
    const res = await getStats(props.scopeType, props.scopeId)
    stats.value = res.data
  } catch {
    showError(t('activity.stats.fetch_error'))
  } finally {
    loading.value = false
  }
}

watch(stats, (newStats) => {
  if (!newStats || !chartRef.value) return
  if (chartInstance) chartInstance.destroy()
  // 前のChartインスタンスを確実に破棄してから新規生成（メモリリーク防止）
  chartInstance = new Chart(chartRef.value, {
    type: 'bar',
    data: {
      labels: newStats.monthlyBreakdown.map((m) => m.month),
      datasets: [
        {
          label: t('activity.stats.table_count'),
          data: newStats.monthlyBreakdown.map((m) => m.count),
          backgroundColor: '#6366f1',
        },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
    },
  })
})

onUnmounted(() => {
  if (chartInstance) chartInstance.destroy()
})

onMounted(() => {
  fetchStats()
})
</script>

<template>
  <div class="flex flex-col gap-6">
    <div class="flex flex-wrap items-end gap-3 rounded-xl border border-surface-200 bg-surface-0 p-4">
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ $t('activity.stats.period_start') }}</label>
        <DatePicker v-model="periodStart" date-format="yy/mm/dd" show-icon class="w-44" />
      </div>
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ $t('activity.stats.period_end') }}</label>
        <DatePicker v-model="periodEnd" date-format="yy/mm/dd" show-icon class="w-44" />
      </div>
      <Button :label="$t('activity.stats.aggregate')" icon="pi pi-chart-bar" :loading="loading" @click="fetchStats" />
    </div>

    <PageLoading v-if="loading" size="40px" />

    <template v-if="stats && !loading">
      <div class="grid grid-cols-3 gap-4">
        <div class="rounded-xl border border-surface-200 bg-surface-0 p-4 text-center">
          <div class="text-2xl font-bold text-indigo-500">{{ stats.totalActivities }}</div>
          <div class="mt-1 text-sm text-surface-500">{{ $t('activity.stats.total_activities') }}</div>
        </div>
        <div class="rounded-xl border border-surface-200 bg-surface-0 p-4 text-center">
          <div class="text-2xl font-bold text-indigo-500">{{ stats.totalParticipants }}</div>
          <div class="mt-1 text-sm text-surface-500">{{ $t('activity.stats.total_participants') }}</div>
        </div>
        <div class="rounded-xl border border-surface-200 bg-surface-0 p-4 text-center">
          <div class="text-2xl font-bold text-indigo-500">{{ stats.averageParticipants.toFixed(1) }}</div>
          <div class="mt-1 text-sm text-surface-500">{{ $t('activity.stats.avg_participants') }}</div>
        </div>
      </div>

      <div class="rounded-xl border border-surface-200 bg-surface-0 p-4">
        <h2 class="mb-3 text-sm font-semibold">{{ $t('activity.stats.monthly_chart_title') }}</h2>
        <div class="relative h-64">
          <canvas ref="chartRef" />
        </div>
      </div>

      <div class="rounded-xl border border-surface-200 bg-surface-0 p-4">
        <DataTable :value="stats.monthlyBreakdown" striped-rows class="text-sm">
          <Column field="month" :header="$t('activity.stats.table_month')" />
          <Column field="count" :header="$t('activity.stats.table_count')" />
        </DataTable>
      </div>
    </template>

    <div v-if="!stats && !loading" class="py-12 text-center text-surface-400">
      <i class="pi pi-chart-bar mb-3 text-4xl text-surface-300" />
      <p>{{ $t('activity.stats.aggregate') }}</p>
    </div>
  </div>
</template>
