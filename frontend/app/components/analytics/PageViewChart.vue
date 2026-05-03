<script setup lang="ts">
import { Chart as ChartJS, CategoryScale, LinearScale, PointElement, LineElement, BarElement, Title, Tooltip, Legend, Filler, type ChartConfiguration } from 'chart.js'
import type { DailyPageView, MonthlyPageView } from '~/types/analytics'

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, BarElement, Title, Tooltip, Legend, Filler)

const props = defineProps<{
  daily: DailyPageView[]
  monthly: MonthlyPageView[]
}>()

const viewMode = ref<'daily' | 'monthly'>('daily')
const chartCanvas = ref<HTMLCanvasElement | null>(null)
let chartInstance: ChartJS | null = null

const dailyChartData = computed(() => ({
  labels: props.daily.map(d => d.date),
  datasets: [
    {
      label: 'ページビュー',
      data: props.daily.map(d => d.views),
      borderColor: '#6366f1',
      backgroundColor: 'rgba(99, 102, 241, 0.1)',
      fill: true,
      tension: 0.3,
    },
    {
      label: 'ユニーク訪問者',
      data: props.daily.map(d => d.uniqueVisitors),
      borderColor: '#22c55e',
      backgroundColor: 'rgba(34, 197, 94, 0.1)',
      fill: true,
      tension: 0.3,
    },
  ],
}))

const monthlyChartData = computed(() => ({
  labels: props.monthly.map(m => m.month),
  datasets: [
    {
      label: 'ページビュー',
      data: props.monthly.map(m => m.views),
      backgroundColor: '#6366f1',
    },
    {
      label: 'ユニーク訪問者',
      data: props.monthly.map(m => m.uniqueVisitors),
      backgroundColor: '#22c55e',
    },
  ],
}))

const chartOptions = {
  responsive: true,
  maintainAspectRatio: false,
  plugins: { legend: { position: 'top' as const } },
  scales: { y: { beginAtZero: true } },
}

function renderChart() {
  if (!chartCanvas.value) return
  chartInstance?.destroy()
  const data = viewMode.value === 'daily' ? dailyChartData.value : monthlyChartData.value
  const type = viewMode.value === 'daily' ? 'line' : 'bar'
  const config: ChartConfiguration = { type, data, options: chartOptions }
  chartInstance = new ChartJS(chartCanvas.value, config)
}

watch(viewMode, () => renderChart())
onMounted(() => renderChart())
onUnmounted(() => chartInstance?.destroy())
</script>

<template>
  <div>
    <div class="mb-4 flex justify-end">
      <SelectButton
        v-model="viewMode"
        :options="[{ label: '日別', value: 'daily' }, { label: '月別', value: 'monthly' }]"
        option-label="label"
        option-value="value"
      />
    </div>
    <div class="h-80">
      <canvas ref="chartCanvas" />
      <p class="mt-2 text-center text-xs text-surface-500">
        {{ viewMode === 'daily' ? '直近30日間' : '月間推移' }}のアクセス統計
      </p>
    </div>
  </div>
</template>
