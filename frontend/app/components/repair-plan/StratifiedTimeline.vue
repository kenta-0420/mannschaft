<script setup lang="ts">
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  BarElement,
  LineElement,
  PointElement,
  Title,
  Tooltip,
  Legend,
  type ChartConfiguration,
  type TooltipItem,
} from 'chart.js'
import type { RepairPlanTimelineResponse, TimelineLayer } from '~/types/repairPlanTimeline'

ChartJS.register(CategoryScale, LinearScale, BarElement, LineElement, PointElement, Title, Tooltip, Legend)

const props = defineProps<{
  data: RepairPlanTimelineResponse
}>()

const { t } = useI18n()

const CATEGORY_COLORS: string[] = [
  '#6366f1',
  '#22c55e',
  '#f59e0b',
  '#ef4444',
  '#3b82f6',
  '#a855f7',
  '#14b8a6',
  '#f97316',
  '#ec4899',
  '#64748b',
]

const activeLayers = ref<TimelineLayer[]>(['amount', 'chairperson', 'cpi'])

const layerOptions: { key: TimelineLayer; labelKey: string; disabled?: boolean }[] = [
  { key: 'amount', labelKey: 'repair_plan.timeline.layer.amount' },
  { key: 'chairperson', labelKey: 'repair_plan.timeline.layer.chairperson' },
  { key: 'cpi', labelKey: 'repair_plan.timeline.layer.cpi' },
  { key: 'minutes', labelKey: 'repair_plan.timeline.layer.minutes', disabled: true },
]

function isLayerActive(layer: TimelineLayer): boolean {
  return activeLayers.value.includes(layer)
}

function toggleLayer(layer: TimelineLayer) {
  if (isLayerActive(layer)) {
    activeLayers.value = activeLayers.value.filter((l) => l !== layer)
  } else {
    activeLayers.value = [...activeLayers.value, layer]
  }
}

const chartCanvas = ref<HTMLCanvasElement | null>(null)
let chartInstance: ChartJS | null = null

let debounceTimer: ReturnType<typeof setTimeout> | null = null

function buildChartConfig(): ChartConfiguration {
  const amountVisible = activeLayers.value.includes('amount')
  const cpiVisible = activeLayers.value.includes('cpi')
  const chairpersonVisible = activeLayers.value.includes('chairperson')

  return {
    type: 'bar',
    data: {
      labels: props.data.labels.map(String),
      datasets: [
        ...props.data.categories.map((cat, i) => ({
          type: 'bar' as const,
          label: t(`repair_plan.category.${cat}`, cat),
          data: props.data.labels.map((y) => props.data.amountByYearAndCategory[String(y)]?.[cat] ?? 0),
          backgroundColor: CATEGORY_COLORS[i % CATEGORY_COLORS.length],
          stack: 'amount',
          hidden: !amountVisible,
          yAxisID: 'y',
        })),
        {
          type: 'line' as const,
          label: t('repair_plan.timeline.layer.cpi'),
          data: props.data.labels.map((y) => props.data.cpiTrendByYear[String(y)] ?? null),
          borderColor: '#f59e0b',
          backgroundColor: 'transparent',
          pointRadius: 2,
          yAxisID: 'y1',
          hidden: !cpiVisible,
        },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { position: 'top' },
        tooltip: {
          callbacks: {
            afterBody: (items: TooltipItem<'bar'>[]) => {
              if (!chairpersonVisible) return []
              const dataIndex = items[0]?.dataIndex ?? 0
              const year = String(props.data.labels[dataIndex])
              const name = props.data.chairpersonByYear[year]
              return name ? [`${t('repair_plan.timeline.layer.chairperson')}: ${name}`] : []
            },
          },
        },
      },
      scales: {
        y: {
          stacked: true,
          title: {
            display: true,
            text: t('repair_plan.timeline.layer.amount') + '（円）',
          },
        },
        y1: {
          type: 'linear',
          position: 'right',
          title: {
            display: true,
            text: 'CPI (2024=100)',
          },
          grid: {
            drawOnChartArea: false,
          },
        },
      },
    },
  }
}

function renderChart() {
  if (!chartCanvas.value) return
  if (chartInstance) {
    chartInstance.destroy()
  }
  chartInstance = new ChartJS(chartCanvas.value, buildChartConfig())
}

function scheduleChartUpdate() {
  if (debounceTimer !== null) {
    clearTimeout(debounceTimer)
  }
  debounceTimer = setTimeout(() => {
    if (chartInstance) {
      const datasets = buildChartConfig().data.datasets
      chartInstance.data.datasets = datasets
      chartInstance.update('none')
    }
    debounceTimer = null
  }, 300)
}

watch(activeLayers, () => scheduleChartUpdate(), { deep: true })
watch(
  () => props.data,
  () => renderChart(),
)

onMounted(() => renderChart())
onBeforeUnmount(() => {
  if (debounceTimer !== null) {
    clearTimeout(debounceTimer)
  }
  chartInstance?.destroy()
})
</script>

<template>
  <div class="space-y-4">
    <!-- レイヤートグル -->
    <div class="flex flex-wrap items-center gap-4">
      <span class="text-sm font-medium text-surface-600 dark:text-surface-300">
        {{ $t('repair_plan.timeline.layer.amount') }}表示:
      </span>
      <div
        v-for="layer in layerOptions"
        :key="layer.key"
        class="flex items-center gap-1.5"
      >
        <Checkbox
          :model-value="isLayerActive(layer.key)"
          :binary="true"
          :disabled="layer.disabled"
          :input-id="`layer-${layer.key}`"
          @update:model-value="() => toggleLayer(layer.key)"
        />
        <label
          :for="`layer-${layer.key}`"
          class="cursor-pointer select-none text-sm"
          :class="layer.disabled ? 'text-surface-400 dark:text-surface-500' : 'text-surface-700 dark:text-surface-200'"
        >
          {{ $t(layer.labelKey) }}
          <span v-if="layer.disabled" class="ml-1 text-xs text-surface-400">（準備中）</span>
        </label>
      </div>
    </div>

    <!-- チャートエリア -->
    <div class="relative h-96 w-full print:h-64">
      <canvas ref="chartCanvas" />
    </div>

    <!-- CPI 注記 -->
    <p class="text-xs text-surface-400 dark:text-surface-500">
      {{ $t('repair_plan.timeline.cpi_note') }}
    </p>
  </div>
</template>

<style scoped>
@media print {
  .h-96 {
    height: 16rem;
  }
}
</style>
