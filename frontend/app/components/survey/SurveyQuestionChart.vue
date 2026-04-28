<script setup lang="ts">
import {
  Chart as ChartJS,
  ArcElement,
  CategoryScale,
  LinearScale,
  BarElement,
  BarController,
  DoughnutController,
  Tooltip,
  Legend,
} from 'chart.js'
import type { SurveyResultSummary } from '~/types/survey'

// chart.js 4.x では Controller も明示登録が必要。
// 登録漏れがあると `"<type>" is not a registered controller.` で実行時エラーになる。
ChartJS.register(
  ArcElement,
  CategoryScale,
  LinearScale,
  BarElement,
  BarController,
  DoughnutController,
  Tooltip,
  Legend,
)

const props = defineProps<{
  result: SurveyResultSummary
}>()

const { t } = useI18n()

const canvasRef = ref<HTMLCanvasElement | null>(null)
let chartInstance: ChartJS | null = null

const CHART_COLORS = [
  '#6366f1', '#22c55e', '#f59e0b', '#ef4444', '#8b5cf6',
  '#06b6d4', '#ec4899', '#84cc16', '#f97316', '#14b8a6',
]

function buildConfig() {
  const { questionType, optionResults } = props.result

  if (questionType === 'RATING') {
    return {
      type: 'bar' as const,
      data: {
        labels: optionResults.map((o) => o.optionText),
        datasets: [
          {
            label: t('surveys.detail.results.responsesLabel'),
            data: optionResults.map((o) => o.count),
            backgroundColor: '#6366f1',
            borderRadius: 4,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        indexAxis: 'y' as const,
        plugins: { legend: { display: false } },
        scales: { x: { beginAtZero: true, ticks: { stepSize: 1 } } },
      },
    }
  }

  // SINGLE_CHOICE, MULTIPLE_CHOICE
  return {
    type: 'doughnut' as const,
    data: {
      labels: optionResults.map((o) => `${o.optionText} (${o.percentage}%)`),
      datasets: [
        {
          data: optionResults.map((o) => o.count),
          backgroundColor: CHART_COLORS.slice(0, optionResults.length),
          borderWidth: 1,
        },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: {
          position: 'right' as const,
          labels: { font: { size: 11 }, padding: 8 },
        },
      },
    },
  }
}

function initChart() {
  if (!canvasRef.value) return
  chartInstance?.destroy()
  chartInstance = new ChartJS(canvasRef.value, buildConfig() as ConstructorParameters<typeof ChartJS>[1])
}

onMounted(initChart)
onUnmounted(() => {
  chartInstance?.destroy()
  chartInstance = null
})
</script>

<template>
  <div class="mb-3 rounded-lg bg-surface-50 p-3 dark:bg-surface-700/30">
    <p class="mb-2 text-sm font-semibold text-surface-700 dark:text-surface-200">
      {{ result.questionText }}
      <span class="ml-1 text-xs font-normal text-surface-400">{{ result.totalResponses }}{{ t('surveys.detail.results.responsesUnit') }}</span>
    </p>

    <!-- 選択式・評価: グラフ表示 -->
    <template v-if="result.questionType === 'SINGLE_CHOICE' || result.questionType === 'MULTIPLE_CHOICE' || result.questionType === 'RATING'">
      <div
        v-if="result.optionResults.length > 0"
        :class="result.questionType === 'RATING' ? 'h-32' : 'h-44'"
        class="relative"
      >
        <canvas ref="canvasRef" />
      </div>
      <p v-else class="text-xs text-surface-400">{{ t('surveys.detail.results.noData') }}</p>
    </template>

    <!-- テキスト回答 -->
    <template v-else-if="result.questionType === 'TEXT'">
      <ul v-if="result.textResponses?.length" class="space-y-1">
        <li
          v-for="(text, i) in result.textResponses.slice(0, 8)"
          :key="i"
          class="rounded bg-white px-3 py-1.5 text-xs text-surface-600 dark:bg-surface-800 dark:text-surface-300"
        >
          {{ text }}
        </li>
        <li v-if="(result.textResponses?.length ?? 0) > 8" class="px-3 text-xs text-surface-400">
          {{ t('surveys.detail.results.moreCount', { count: result.textResponses!.length - 8 }) }}
        </li>
      </ul>
      <p v-else class="text-xs text-surface-400">{{ t('surveys.detail.results.noTextResponses') }}</p>
    </template>

    <!-- 日付回答 -->
    <template v-else>
      <div class="space-y-1">
        <div
          v-for="o in result.optionResults"
          :key="o.optionId"
          class="flex items-center justify-between text-xs"
        >
          <span class="text-surface-600 dark:text-surface-300">{{ o.optionText }}</span>
          <span class="text-surface-400">{{ o.count }}{{ t('surveys.detail.results.responsesUnit') }} ({{ o.percentage }}%)</span>
        </div>
      </div>
    </template>
  </div>
</template>
