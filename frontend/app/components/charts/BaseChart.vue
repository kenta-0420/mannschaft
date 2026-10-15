<script setup lang="ts">
/**
 * F08.10 共通チャートラッパー（04_frontend_and_ux.md §G.3 / §G.8）。
 *
 * chart.js@4 を流用し、radar / line / doughnut / bar を 1 つの土台で扱う。
 * Phase 3-C（個人分析）・Phase 3-D（チーム分析）双方で再利用する。
 *
 * 設計ポイント:
 * - **要素登録の集約**: 各チャート種別が必要とする Controller / Scale / Element を
 *   このコンポーネント 1 箇所で `Chart.register` する（重複 register 回避）。
 *   既存 `ActivityStatsPanel.vue` は Bar 系のみ登録のため、radar/line/doughnut 用の
 *   要素（RadarController/RadialLinearScale/PointElement/LineElement/ArcElement 等）を追加。
 * - **SSR 安全**: chart.js は canvas（ブラウザ API）依存。テンプレートを `<ClientOnly>` で
 *   ラップし、`onMounted` 後にのみ Chart を生成する（SSR 中に canvas を触らない）。
 * - **空状態**: データが無い / 全系列が空（全 null）のとき、空キャンバスを描かず
 *   DashboardEmptyState を表示する（§G.8）。null データポイントは描画スキップ（0/NaN を描かない）。
 * - **再描画 / 破棄**: props 変化を deep watch して再生成、unmount で destroy（メモリリーク防止）。
 */
import {
  ArcElement,
  BarController,
  BarElement,
  CategoryScale,
  Chart as ChartJS,
  DoughnutController,
  Filler,
  Legend,
  LineController,
  LineElement,
  LinearScale,
  PointElement,
  RadarController,
  RadialLinearScale,
  Tooltip,
  type ChartData,
  type ChartOptions,
  type ChartType,
} from 'chart.js'

// radar / line / doughnut / bar が必要とする要素を 1 箇所で集約登録する。
// register は冪等（同一要素の再登録は無害）だが、土台を 1 コンポに集約することで
// 各ページが個別に register する重複を避ける。
ChartJS.register(
  // controllers
  BarController,
  LineController,
  RadarController,
  DoughnutController,
  // scales
  CategoryScale,
  LinearScale,
  RadialLinearScale,
  // elements
  BarElement,
  LineElement,
  PointElement,
  ArcElement,
  // plugins
  Filler,
  Legend,
  Tooltip,
)

/** 本ラッパーが扱うチャート種別（コア 4 種・§G.3 の表に対応） */
type SupportedChartType = Extract<ChartType, 'radar' | 'line' | 'doughnut' | 'bar'>

const props = withDefaults(
  defineProps<{
    /** チャート種別 */
    type: SupportedChartType
    /** chart.js の data（labels + datasets） */
    data: ChartData<SupportedChartType>
    /** chart.js の options（未指定時は responsive 既定を使う） */
    options?: ChartOptions<SupportedChartType>
    /** 空状態に表示するメッセージ（i18n 済みの文字列を渡す） */
    emptyMessage?: string
    /** 空状態アイコン（PrimeIcons クラス名） */
    emptyIcon?: string
    /** キャンバス高さ（Tailwind の任意値クラス。既定 h-72） */
    heightClass?: string
  }>(),
  {
    options: undefined,
    emptyMessage: undefined,
    emptyIcon: 'pi pi-chart-bar',
    heightClass: 'h-72',
  },
)

const { t } = useI18n()

const canvasRef = ref<HTMLCanvasElement | null>(null)
let chartInstance: ChartJS | null = null

/**
 * データが「描画に値するか」を判定する。
 * - labels も datasets も無ければ空。
 * - 全 dataset のデータ点が空 / 全て null（goalsPer90=null 等）なら空とみなす。
 *   0 は有効値として扱う（全 0 のグラフも「記録はある」状態なので描画する）。
 */
const hasRenderableData = computed<boolean>(() => {
  const datasets = props.data.datasets ?? []
  if (datasets.length === 0) return false
  return datasets.some((ds) => {
    const arr = (ds.data ?? []) as unknown[]
    return arr.some((v) => v !== null && v !== undefined)
  })
})

const resolvedOptions = computed<ChartOptions<SupportedChartType>>(() => {
  if (props.options) return props.options
  return {
    responsive: true,
    maintainAspectRatio: false,
    plugins: { legend: { position: 'top' } },
  } as ChartOptions<SupportedChartType>
})

function renderChart(): void {
  if (!canvasRef.value || !hasRenderableData.value) return
  chartInstance?.destroy()
  chartInstance = new ChartJS(canvasRef.value, {
    type: props.type,
    data: props.data,
    options: resolvedOptions.value,
    // null データポイントは線を繋いで補間せず、欠損として扱う（NaN/0 を描かない）。
  } as never)
}

// props（種別・データ・オプション）変化で再生成。空→有データの遷移にも追従するよう
// hasRenderableData も監視対象に含める（nextTick で canvas マウント後に描画）。
watch(
  [() => props.type, () => props.data, () => props.options, hasRenderableData],
  async () => {
    await nextTick()
    renderChart()
  },
  { deep: true },
)

onMounted(renderChart)
onUnmounted(() => {
  chartInstance?.destroy()
  chartInstance = null
})
</script>

<template>
  <ClientOnly>
    <div :class="heightClass" class="relative w-full" data-testid="base-chart">
      <DashboardEmptyState
        v-if="!hasRenderableData"
        :icon="emptyIcon"
        :message="emptyMessage ?? t('match.analytics.empty.no_data')"
      />
      <canvas v-else ref="canvasRef" />
    </div>
    <template #fallback>
      <div :class="heightClass" class="flex w-full items-center justify-center">
        <PageLoading />
      </div>
    </template>
  </ClientOnly>
</template>
