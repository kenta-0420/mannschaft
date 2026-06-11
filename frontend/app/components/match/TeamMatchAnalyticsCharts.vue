<script setup lang="ts">
/**
 * F08.10 チーム分析チャート群（04_frontend_and_ux.md §G.3 / §F.3）。
 *
 * `TeamMatchStatsResponse` を受け取り、サマリ指標カード・勝敗サマリ(doughnut)・
 * 選手別得点ランキング(bar)・直近フォーム勝点推移(line)・種別別試合数(bar) を描画する。
 * チーム分析ページ（pages/teams/[id]/match-analytics.vue）専用で、ページ本体を
 * 300 行以内に保つためチャート群をまとめて切り出す。
 *
 * データ整形は useTeamMatchAnalyticsCharts に委譲し、本コンポーネントは表示に専念する。
 * 色覚配慮（§G.12）として、指標カードは色＋アイコン＋ラベルを併用する。
 */
import type { TeamMatchStatsResponse } from '~/types/match'
import type { ChartOptions } from 'chart.js'

const props = defineProps<{
  stats: TeamMatchStatsResponse
}>()

const { t } = useI18n()
const charts = useTeamMatchAnalyticsCharts()

const labels = computed(() => ({
  kind: (kind: string) =>
    kind ? t(`match.kind.${kind}`) : t('match.analytics.unknown_kind'),
  result: {
    wins: t('match.list.result.win'),
    draws: t('match.list.result.draw'),
    losses: t('match.list.result.loss'),
  },
  series: {
    points: t('match.analytics.team.series.points'),
    playerGoals: t('match.analytics.team.series.player_goals'),
    matchesByKind: t('match.analytics.team.series.matches_by_kind'),
  },
}))

const resultData = computed(() => charts.buildResultSummary(props.stats, labels.value))
const playerGoalsData = computed(() =>
  charts.buildPlayerGoalsRanking(props.stats, labels.value),
)
const recentFormData = computed(() => charts.buildRecentFormLine(props.stats, labels.value))
const matchesByKindData = computed(() =>
  charts.buildMatchesByKind(props.stats, labels.value),
)

/** 選手別ランキングは横棒（indexAxis: 'y'）で名前を読みやすくする。 */
const horizontalBarOptions = computed<ChartOptions<'bar'>>(() => ({
  responsive: true,
  maintainAspectRatio: false,
  indexAxis: 'y',
  plugins: { legend: { display: false } },
  scales: {
    x: { beginAtZero: true, ticks: { precision: 0 } },
  },
}))

/** 直近フォームは勝点累積（左軸・整数）。 */
const lineOptions = computed<ChartOptions<'line'>>(() => ({
  responsive: true,
  maintainAspectRatio: false,
  plugins: { legend: { position: 'top' } },
  scales: {
    y: { beginAtZero: true, ticks: { precision: 0 } },
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
  const wdl = `${s.wins ?? 0}/${s.draws ?? 0}/${s.losses ?? 0}`
  const gd = s.goalDifference ?? 0
  return [
    {
      key: 'matches',
      label: t('match.analytics.team.metric.total_matches'),
      value: String(s.totalMatches ?? 0),
      icon: 'pi pi-flag',
    },
    {
      key: 'wdl',
      label: t('match.analytics.team.metric.win_draw_loss'),
      value: wdl,
      icon: 'pi pi-chart-bar',
    },
    {
      key: 'goalsFor',
      label: t('match.analytics.team.metric.goals_for'),
      value: String(s.totalGoalsFor ?? 0),
      icon: 'pi pi-circle-fill',
    },
    {
      key: 'goalsAgainst',
      label: t('match.analytics.team.metric.goals_against'),
      value: String(s.totalGoalsAgainst ?? 0),
      icon: 'pi pi-shield',
    },
    {
      key: 'goalDifference',
      label: t('match.analytics.team.metric.goal_difference'),
      value: gd > 0 ? `+${gd}` : String(gd),
      icon: 'pi pi-chart-line',
    },
  ]
})
</script>

<template>
  <div class="flex flex-col gap-6">
    <!-- サマリ指標（色＋アイコン＋ラベル併用・§G.12） -->
    <div class="grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-5">
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

    <div class="grid grid-cols-1 gap-6 lg:grid-cols-2">
      <SectionCard :title="t('match.analytics.team.chart.result_title')">
        <BaseChart
          type="doughnut"
          :data="resultData"
          :empty-message="t('match.analytics.empty.no_data')"
          empty-icon="pi pi-chart-pie"
        />
      </SectionCard>

      <SectionCard :title="t('match.analytics.team.chart.player_goals_title')">
        <BaseChart
          type="bar"
          :data="playerGoalsData"
          :options="horizontalBarOptions"
          :empty-message="t('match.analytics.empty.no_goals')"
          empty-icon="pi pi-chart-bar"
        />
      </SectionCard>

      <SectionCard :title="t('match.analytics.team.chart.recent_form_title')">
        <BaseChart
          type="line"
          :data="recentFormData"
          :options="lineOptions"
          :empty-message="t('match.analytics.empty.no_trend')"
          empty-icon="pi pi-chart-line"
        />
      </SectionCard>

      <SectionCard :title="t('match.analytics.team.chart.matches_by_kind_title')">
        <BaseChart
          type="bar"
          :data="matchesByKindData"
          :empty-message="t('match.analytics.empty.no_data')"
          empty-icon="pi pi-chart-bar"
        />
      </SectionCard>
    </div>
  </div>
</template>
