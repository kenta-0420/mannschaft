<script setup lang="ts">
/**
 * F09.17 Phase 11-c-4 — 広告主レポート用 日次推移チャート。
 *
 * <p>chart.js の line chart で、配信数 (棒) + 開封率 (折線) + クリック率 (折線) を
 * 同一 X 軸 (日付) で重ね表示する。
 * 配信数は左 y 軸、開封率/クリック率は右 y 軸（0〜1.0 スケール）。</p>
 *
 * <p>chart.js は既存 PageViewChart 等で利用済みの依存。本コンポーネントでは
 * 必要な要素を都度 register する。SSR 環境では canvas 描画しないため `onMounted` 後に
 * 初期化する。</p>
 */
import {
  BarController,
  BarElement,
  CategoryScale,
  Chart as ChartJS,
  Filler,
  Legend,
  LinearScale,
  LineController,
  LineElement,
  PointElement,
  Title,
  Tooltip,
  type ChartConfiguration,
} from 'chart.js'
import type { AdCampaignReportDailyPoint } from '~/types/adMessagingCampaign'

ChartJS.register(
  BarController,
  BarElement,
  CategoryScale,
  Filler,
  Legend,
  LinearScale,
  LineController,
  LineElement,
  PointElement,
  Title,
  Tooltip,
)

interface Props {
  /** 日次データ */
  daily: AdCampaignReportDailyPoint[]
}

const props = defineProps<Props>()

const { t } = useI18n()

const chartCanvas = ref<HTMLCanvasElement | null>(null)
let chartInstance: ChartJS | null = null

/**
 * delivered 配信数を分母として open_rate / click_rate を算出する。
 * 分母 0 のときは null（chart.js 上は空白プロット）。
 */
function safeRate(numerator: number, denominator: number): number | null {
  if (denominator <= 0) return null
  return numerator / denominator
}

const chartData = computed(() => ({
  labels: props.daily.map((d) => d.date),
  datasets: [
    {
      type: 'bar' as const,
      label: t('advertising.pages.advertiser_campaign_report.chart_delivered'),
      data: props.daily.map((d) => d.delivered),
      backgroundColor: 'rgba(99, 102, 241, 0.4)',
      borderColor: '#6366f1',
      yAxisID: 'yCount',
      order: 2,
    },
    {
      type: 'line' as const,
      label: t('advertising.pages.advertiser_campaign_report.chart_open_rate'),
      data: props.daily.map((d) => safeRate(d.opened, d.delivered)),
      borderColor: '#22c55e',
      backgroundColor: 'rgba(34, 197, 94, 0.15)',
      yAxisID: 'yRate',
      tension: 0.3,
      spanGaps: true,
      order: 1,
    },
    {
      type: 'line' as const,
      label: t('advertising.pages.advertiser_campaign_report.chart_click_rate'),
      data: props.daily.map((d) => safeRate(d.clicked, d.delivered)),
      borderColor: '#f97316',
      backgroundColor: 'rgba(249, 115, 22, 0.15)',
      yAxisID: 'yRate',
      tension: 0.3,
      spanGaps: true,
      order: 1,
    },
  ],
}))

const chartOptions = computed(() => ({
  responsive: true,
  maintainAspectRatio: false,
  interaction: { mode: 'index' as const, intersect: false },
  plugins: {
    legend: { position: 'top' as const },
    title: { display: false },
  },
  scales: {
    yCount: {
      type: 'linear' as const,
      position: 'left' as const,
      beginAtZero: true,
      ticks: { precision: 0 },
    },
    yRate: {
      type: 'linear' as const,
      position: 'right' as const,
      beginAtZero: true,
      max: 1,
      grid: { drawOnChartArea: false },
      ticks: {
        callback: (value: string | number) => {
          const n = typeof value === 'number' ? value : Number(value)
          return `${Math.round(n * 100)}%`
        },
      },
    },
  },
}))

function renderChart() {
  if (!chartCanvas.value) return
  chartInstance?.destroy()
  const config: ChartConfiguration = {
    // mixed chart: 個別 dataset 側の type をそれぞれ尊重させるため bar を base にする
    type: 'bar',
    data: chartData.value,
    options: chartOptions.value,
  }
  chartInstance = new ChartJS(chartCanvas.value, config)
}

watch(
  () => props.daily,
  () => renderChart(),
  { deep: true },
)

onMounted(renderChart)
onUnmounted(() => chartInstance?.destroy())
</script>

<template>
  <div class="h-80" data-testid="ad-report-chart">
    <canvas ref="chartCanvas" />
  </div>
</template>
