<script setup lang="ts">
/**
 * F08.10 個人分析チャート群（04_frontend_and_ux.md §G.3）。
 *
 * `UserMatchStatsResponse` を受け取り、サマリ指標カード・自己ベスト（§G.15 (d)）・
 * radar/line/doughnut/bar の 4 チャートをまとめて描画する。自分用ページ
 * （pages/me/match-analytics.vue）とメンバー用ページ
 * （pages/teams/[id]/members/[userId]/match-analytics.vue）で共有し、
 * 各ページ本体を 300 行以内に保つ。
 *
 * データ整形は useMatchAnalyticsCharts に委譲し、本コンポーネントは表示に専念する。
 * 色覚配慮（§G.12）として、指標カードは色＋アイコン＋ラベルを併用する。
 */
import type { UserMatchStatsResponse } from '~/types/match'
import type { ChartOptions } from 'chart.js'

const props = defineProps<{
  stats: UserMatchStatsResponse
}>()

const { t } = useI18n()
const charts = useMatchAnalyticsCharts()

const labels = computed(() => ({
  kind: (kind: string) => (kind ? t(`match.kind.${kind}`) : t('match.analytics.unknown_kind')),
  axis: {
    goals: t('match.analytics.axis.goals'),
    assists: t('match.analytics.axis.assists'),
    matches: t('match.analytics.axis.matches'),
    minutes: t('match.analytics.axis.minutes'),
    starterRate: t('match.analytics.axis.starter_rate'),
  },
  series: {
    goals: t('match.analytics.series.goals'),
    minutes: t('match.analytics.series.minutes'),
    matchShare: t('match.analytics.series.match_share'),
    goalsByKind: t('match.analytics.series.goals_by_kind'),
  },
  month: (month: string) => month,
}))

const radarData = computed(() => charts.buildRadar(props.stats, labels.value))
const lineData = computed(() => charts.buildMonthlyLine(props.stats, labels.value))
const kindShareData = computed(() => charts.buildKindShare(props.stats, labels.value))
const goalsByKindData = computed(() => charts.buildGoalsByKind(props.stats, labels.value))
const personalBest = computed(() => charts.computePersonalBest(props.stats))
const goalsPer90Display = computed(() => charts.formatGoalsPer90(props.stats.goalsPer90))

/** radar は 0..100 正規化なので軸最大を固定する。 */
const radarOptions = computed<ChartOptions<'radar'>>(() => ({
  responsive: true,
  maintainAspectRatio: false,
  plugins: { legend: { position: 'top' } },
  scales: {
    r: { beginAtZero: true, suggestedMax: 100 },
  },
}))

/** line は得点（左軸）と出場時間（右軸）の二軸。 */
const lineOptions = computed<ChartOptions<'line'>>(() => ({
  responsive: true,
  maintainAspectRatio: false,
  interaction: { mode: 'index', intersect: false },
  plugins: { legend: { position: 'top' } },
  scales: {
    yGoals: { type: 'linear', position: 'left', beginAtZero: true, ticks: { precision: 0 } },
    yMinutes: {
      type: 'linear',
      position: 'right',
      beginAtZero: true,
      grid: { drawOnChartArea: false },
    },
  },
}))

interface MetricCard {
  key: string
  label: string
  value: string
  icon: string
}

const metricCards = computed<MetricCard[]>(() => {
  const s = props.stats
  return [
    { key: 'matches', label: t('match.analytics.metric.total_matches'), value: String(s.totalMatches ?? 0), icon: 'pi pi-flag' },
    { key: 'minutes', label: t('match.analytics.metric.total_minutes'), value: String(s.totalMinutes ?? 0), icon: 'pi pi-clock' },
    { key: 'goals', label: t('match.analytics.metric.goals'), value: String(s.goals ?? 0), icon: 'pi pi-circle-fill' },
    { key: 'assists', label: t('match.analytics.metric.assists'), value: String(s.assists ?? 0), icon: 'pi pi-share-alt' },
    { key: 'goalsPer90', label: t('match.analytics.metric.goals_per90'), value: goalsPer90Display.value, icon: 'pi pi-chart-line' },
    { key: 'starterRate', label: t('match.analytics.metric.starter_rate'), value: `${Math.round((s.starterRate ?? 0) * 100)}%`, icon: 'pi pi-star' },
  ]
})
</script>

<template>
  <div class="flex flex-col gap-6">
    <!-- サマリ指標（色＋アイコン＋ラベル併用・§G.12） -->
    <div class="grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-6">
      <div
        v-for="card in metricCards"
        :key="card.key"
        class="flex flex-col items-center gap-1 rounded-xl border border-surface-200 bg-surface-0 p-3 text-center dark:border-surface-700 dark:bg-surface-900"
      >
        <i :class="card.icon" class="text-xl text-primary" />
        <div class="text-xl font-bold">{{ card.value }}</div>
        <div class="text-xs text-surface-500">{{ card.label }}</div>
      </div>
    </div>

    <!-- 自己ベスト（§G.15 (d)） -->
    <SectionCard :title="t('match.analytics.best.title')">
      <div class="grid grid-cols-2 gap-4">
        <div class="rounded-lg bg-surface-50 p-3 text-center dark:bg-surface-800">
          <div class="text-2xl font-bold text-primary">{{ personalBest.topGoals }}</div>
          <div class="mt-1 text-xs text-surface-500">{{ t('match.analytics.best.top_goals') }}</div>
        </div>
        <div class="rounded-lg bg-surface-50 p-3 text-center dark:bg-surface-800">
          <div class="text-2xl font-bold text-primary">{{ personalBest.longestMinutes }}</div>
          <div class="mt-1 text-xs text-surface-500">{{ t('match.analytics.best.longest_minutes') }}</div>
        </div>
      </div>
    </SectionCard>

    <div class="grid grid-cols-1 gap-6 lg:grid-cols-2">
      <SectionCard :title="t('match.analytics.chart.radar_title')">
        <BaseChart
          type="radar"
          :data="radarData"
          :options="radarOptions"
          :empty-message="t('match.analytics.empty.no_data')"
          empty-icon="pi pi-chart-pie"
        />
      </SectionCard>

      <SectionCard :title="t('match.analytics.chart.line_title')">
        <BaseChart
          type="line"
          :data="lineData"
          :options="lineOptions"
          :empty-message="t('match.analytics.empty.no_trend')"
          empty-icon="pi pi-chart-line"
        />
      </SectionCard>

      <SectionCard :title="t('match.analytics.chart.doughnut_title')">
        <BaseChart
          type="doughnut"
          :data="kindShareData"
          :empty-message="t('match.analytics.empty.no_data')"
          empty-icon="pi pi-chart-pie"
        />
      </SectionCard>

      <SectionCard :title="t('match.analytics.chart.bar_title')">
        <BaseChart
          type="bar"
          :data="goalsByKindData"
          :empty-message="t('match.analytics.empty.no_goals')"
          empty-icon="pi pi-chart-bar"
        />
      </SectionCard>
    </div>
  </div>
</template>
