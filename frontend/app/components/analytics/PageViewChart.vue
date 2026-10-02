<script setup lang="ts">
import { Chart as ChartJS, BarController, LineController, CategoryScale, LinearScale, PointElement, LineElement, BarElement, Title, Tooltip, Legend, Filler, type ChartConfiguration } from 'chart.js'
import type { DailyPageView, MonthlyPageView } from '~/types/analytics'

// Chart.js v4 は tree-shaking のため使用する Controller も明示登録が必須。
// 本コンポーネントは viewMode で 'line'（日次）/ 'bar'（月次）を切り替えるため、
// 両方の Controller を登録する（AdReportChart.vue と同じ登録パターン）。
ChartJS.register(BarController, LineController, CategoryScale, LinearScale, PointElement, LineElement, BarElement, Title, Tooltip, Legend, Filler)

const props = defineProps<{
  daily: DailyPageView[]
  monthly: MonthlyPageView[]
}>()

const { t } = useI18n()

const viewMode = ref<'daily' | 'monthly'>('daily')
const chartCanvas = ref<HTMLCanvasElement | null>(null)
let chartInstance: ChartJS | null = null

const dailyChartData = computed(() => ({
  labels: props.daily.map(d => d.date),
  datasets: [
    {
      label: t('analytics.chart.views'),
      data: props.daily.map(d => d.views),
      borderColor: '#6366f1',
      backgroundColor: 'rgba(99, 102, 241, 0.1)',
      fill: true,
      tension: 0.3,
    },
    {
      label: t('analytics.summary.uniqueVisitors'),
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
      label: t('analytics.chart.views'),
      data: props.monthly.map(m => m.views),
      backgroundColor: '#6366f1',
    },
    {
      label: t('analytics.summary.uniqueVisitors'),
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

const viewModeOptions = computed(() => [
  { label: t('analytics.chart.daily'), value: 'daily' },
  { label: t('analytics.chart.monthly'), value: 'monthly' },
])

const chartFooterText = computed(() =>
  viewMode.value === 'daily'
    ? t('analytics.chart.recentDays')
    : t('analytics.chart.monthlyTrend'),
)

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
        :options="viewModeOptions"
        option-label="label"
        option-value="value"
        :aria-label="$t('analytics.chart.toggle')"
      />
    </div>
    <div class="h-80">
      <canvas ref="chartCanvas" />
      <p class="mt-2 text-center text-xs text-surface-500">
        {{ chartFooterText }}
      </p>
    </div>
  </div>
</template>
