/**
 * F08.10 個人分析チャートのデータ整形 composable（04_frontend_and_ux.md §G.3 / §G.8）。
 *
 * `UserMatchStatsResponse`（生成型）を chart.js の `ChartData` に変換する純ロジックを集約する。
 * ページ（match-analytics.vue）を 300 行以内に保ち、整形ロジックを単体テスト可能にするため
 * チャート描画から分離する。**any 禁止**・i18n のラベルは呼び出し側から関数で受ける。
 *
 * 整形方針（sports/01_soccer.md §8.6）:
 * - radar: 個人スタッツ分布（得点/アシスト/出場/先発率/警告 等を 0..max 正規化した多軸バランス）
 * - line:  月別の得点・出場時間推移（monthlyTrend[]・データが無い月は描かない）
 * - doughnut: kind 別の出場割合（byKind[].matches）
 * - bar:  kind 別の得点分布（byKind[].goals）
 *
 * null フォールバック（§G.8）: goalsPer90 等の null 指標は radar から除外、
 * monthlyTrend が空なら line は空（BaseChart 側で空状態表示）。
 */
import type { ChartData } from 'chart.js'
import type { UserMatchStatsResponse, TeamMatchStatsResponse } from '~/types/match'
import { OKABE_ITO, withAlpha, paletteColor } from '~/utils/chartColors'

/** i18n ラベル供給関数（呼び出し側で `t('match.analytics.*')` を渡す） */
export interface ChartLabels {
  /** kind コード → 表示ラベル（例: 'LEAGUE' → 'リーグ'） */
  kind: (kind: string) => string
  /** radar 軸ラベル */
  axis: {
    goals: string
    assists: string
    matches: string
    minutes: string
    starterRate: string
  }
  /** 系列ラベル */
  series: {
    goals: string
    minutes: string
    matchShare: string
    goalsByKind: string
  }
  /** 月ラベル整形（YYYY-MM → 表示） */
  month: (month: string) => string
}

/** 自己ベスト（§G.15 (d)・最多得点/最長出場の試合） */
export interface PersonalBest {
  /** 最多得点（1 試合） */
  topGoals: number
  /** 最長出場（分・1 試合） */
  longestMinutes: number
}

export function useMatchAnalyticsCharts() {
  /**
   * radar チャートデータ（個人スタッツ分布）。
   * 各指標を「想定上限」で 0..100 に正規化して同一スケールに乗せる。
   * 母数が 0（試合なし）の場合は空 datasets を返し、BaseChart が空状態を出す。
   */
  function buildRadar(
    stats: UserMatchStatsResponse,
    labels: ChartLabels,
  ): ChartData<'radar'> {
    const totalMatches = stats.totalMatches ?? 0
    if (totalMatches === 0) {
      return { labels: [], datasets: [] }
    }
    // 正規化の分母（1 試合あたりの想定上限）。0 除算を避けるため下限 1。
    const perMatch = (v: number | undefined) =>
      Math.min(100, ((v ?? 0) / totalMatches) * 100)

    const axisLabels = [
      labels.axis.goals,
      labels.axis.assists,
      labels.axis.minutes,
      labels.axis.starterRate,
      labels.axis.matches,
    ]
    // goals/assists は「1 試合 1 件」を 100 とする粗い正規化。minutes は 90 分=100。
    const values = [
      perMatch(stats.goals),
      perMatch(stats.assists),
      Math.min(100, ((stats.avgMinutes ?? 0) / 90) * 100),
      Math.min(100, (stats.starterRate ?? 0) * 100),
      // 試合数自体は到達度として 100 固定（出場している事実）。
      100,
    ]

    return {
      labels: axisLabels,
      datasets: [
        {
          label: labels.series.goals,
          data: values,
          backgroundColor: withAlpha(OKABE_ITO.blue, 0.25),
          borderColor: OKABE_ITO.blue,
          pointBackgroundColor: OKABE_ITO.blue,
        },
      ],
    }
  }

  /**
   * line チャートデータ（月別の得点・出場時間推移）。
   * monthlyTrend が空なら空データを返す。
   */
  function buildMonthlyLine(
    stats: UserMatchStatsResponse,
    labels: ChartLabels,
  ): ChartData<'line'> {
    const trend = stats.monthlyTrend ?? []
    if (trend.length === 0) {
      return { labels: [], datasets: [] }
    }
    return {
      labels: trend.map((m) => labels.month(m.month ?? '')),
      datasets: [
        {
          label: labels.series.goals,
          data: trend.map((m) => m.goals ?? 0),
          borderColor: OKABE_ITO.vermillion,
          backgroundColor: withAlpha(OKABE_ITO.vermillion, 0.15),
          yAxisID: 'yGoals',
          tension: 0.3,
        },
        {
          label: labels.series.minutes,
          data: trend.map((m) => m.minutes ?? 0),
          borderColor: OKABE_ITO.blue,
          backgroundColor: withAlpha(OKABE_ITO.blue, 0.15),
          yAxisID: 'yMinutes',
          tension: 0.3,
        },
      ],
    }
  }

  /**
   * doughnut チャートデータ（kind 別の出場割合）。
   * matches が全て 0 の kind しかなければ空とみなす。
   */
  function buildKindShare(
    stats: UserMatchStatsResponse,
    labels: ChartLabels,
  ): ChartData<'doughnut'> {
    const byKind = (stats.byKind ?? []).filter((k) => (k.matches ?? 0) > 0)
    if (byKind.length === 0) {
      return { labels: [], datasets: [] }
    }
    return {
      labels: byKind.map((k) => labels.kind(k.kind ?? '')),
      datasets: [
        {
          label: labels.series.matchShare,
          data: byKind.map((k) => k.matches ?? 0),
          backgroundColor: byKind.map((_, i) => paletteColor(i)),
          borderWidth: 1,
        },
      ],
    }
  }

  /**
   * bar チャートデータ（kind 別の得点分布）。
   */
  function buildGoalsByKind(
    stats: UserMatchStatsResponse,
    labels: ChartLabels,
  ): ChartData<'bar'> {
    const byKind = stats.byKind ?? []
    if (byKind.length === 0 || byKind.every((k) => (k.goals ?? 0) === 0)) {
      return { labels: [], datasets: [] }
    }
    return {
      labels: byKind.map((k) => labels.kind(k.kind ?? '')),
      datasets: [
        {
          label: labels.series.goalsByKind,
          data: byKind.map((k) => k.goals ?? 0),
          backgroundColor: withAlpha(OKABE_ITO.bluishGreen, 0.7),
          borderColor: OKABE_ITO.bluishGreen,
          borderWidth: 1,
        },
      ],
    }
  }

  /**
   * 自己ベスト（§G.15 (d)）を monthlyTrend から近似抽出する。
   * 試合単位の最大値 API が無いため、月別ピークを当面の自己ベスト指標とする。
   */
  function computePersonalBest(stats: UserMatchStatsResponse): PersonalBest {
    const trend = stats.monthlyTrend ?? []
    const topGoals = trend.reduce((max, m) => Math.max(max, m.goals ?? 0), 0)
    const longestMinutes = trend.reduce((max, m) => Math.max(max, m.minutes ?? 0), 0)
    return { topGoals, longestMinutes }
  }

  /**
   * goalsPer90 の表示値（null は「—」フォールバック・§G.8）。
   */
  function formatGoalsPer90(value: number | null | undefined): string {
    if (value === null || value === undefined) return '—'
    return value.toFixed(2)
  }

  return {
    buildRadar,
    buildMonthlyLine,
    buildKindShare,
    buildGoalsByKind,
    computePersonalBest,
    formatGoalsPer90,
  }
}

/**
 * チーム分析チャートの i18n ラベル供給（呼び出し側で `t('match.analytics.team.*')` を渡す）。
 * 個人分析（{@link ChartLabels}）と異なる軸・系列を持つため別インターフェースとする。
 */
export interface TeamChartLabels {
  /** kind コード → 表示ラベル（'LEAGUE' → 'リーグ'） */
  kind: (kind: string) => string
  /** 勝敗サマリの各セグメント */
  result: {
    wins: string
    draws: string
    losses: string
  }
  /** 系列ラベル */
  series: {
    points: string
    playerGoals: string
    matchesByKind: string
  }
}

/** 直近フォーム（W/D/L 文字列）→ 勝点換算（W=3 / D=1 / L=0）。 */
function formResultToPoints(result: string): number {
  const r = result.toUpperCase()
  if (r === 'W') return 3
  if (r === 'D') return 1
  return 0
}

/**
 * F08.10 チーム分析チャートのデータ整形 composable（04_frontend_and_ux.md §G.3 / §F.3）。
 *
 * `TeamMatchStatsResponse`（生成型）を chart.js の `ChartData` に変換する純ロジック。
 * 個人分析と同様、ページ（teams/[id]/match-analytics.vue）を薄く保ち単体テスト可能にする。
 * **any 禁止**・i18n ラベルは呼び出し側から関数で受ける。
 *
 * 整形方針:
 * - doughnut: 勝/分/敗の割合（wins/draws/losses）。全 0 なら空。
 * - bar(選手): playerRankings の得点上位（displayName 別・降順・上位 8 名）。
 * - line: recentForm（直近 W/D/L）を勝点換算した累積推移。空なら空。
 * - bar(kind): byKind の試合数分布。
 */
export function useTeamMatchAnalyticsCharts() {
  /** 勝敗サマリ（doughnut）。wins/draws/losses が全て 0 なら空。 */
  function buildResultSummary(
    stats: TeamMatchStatsResponse,
    labels: TeamChartLabels,
  ): ChartData<'doughnut'> {
    const wins = stats.wins ?? 0
    const draws = stats.draws ?? 0
    const losses = stats.losses ?? 0
    if (wins + draws + losses === 0) {
      return { labels: [], datasets: [] }
    }
    return {
      labels: [labels.result.wins, labels.result.draws, labels.result.losses],
      datasets: [
        {
          data: [wins, draws, losses],
          backgroundColor: [OKABE_ITO.bluishGreen, OKABE_ITO.yellow, OKABE_ITO.vermillion],
          borderWidth: 1,
        },
      ],
    }
  }

  /** 選手別得点ランキング（bar・上位 8 名・降順）。得点が全て 0 なら空。 */
  function buildPlayerGoalsRanking(
    stats: TeamMatchStatsResponse,
    labels: TeamChartLabels,
  ): ChartData<'bar'> {
    const rankings = (stats.playerRankings ?? [])
      .filter((p) => (p.goals ?? 0) > 0)
      .sort((a, b) => (b.goals ?? 0) - (a.goals ?? 0))
      .slice(0, 8)
    if (rankings.length === 0) {
      return { labels: [], datasets: [] }
    }
    return {
      labels: rankings.map((p) => p.displayName ?? ''),
      datasets: [
        {
          label: labels.series.playerGoals,
          data: rankings.map((p) => p.goals ?? 0),
          backgroundColor: rankings.map((_, i) => paletteColor(i)),
          borderWidth: 1,
        },
      ],
    }
  }

  /** 直近フォームの勝点累積推移（line）。recentForm が空なら空。 */
  function buildRecentFormLine(
    stats: TeamMatchStatsResponse,
    labels: TeamChartLabels,
  ): ChartData<'line'> {
    const form = stats.recentForm ?? []
    if (form.length === 0) {
      return { labels: [], datasets: [] }
    }
    let cumulative = 0
    const points = form.map((r) => (cumulative += formResultToPoints(r)))
    return {
      labels: form.map((_, i) => String(i + 1)),
      datasets: [
        {
          label: labels.series.points,
          data: points,
          borderColor: OKABE_ITO.blue,
          backgroundColor: withAlpha(OKABE_ITO.blue, 0.15),
          tension: 0.3,
          fill: true,
        },
      ],
    }
  }

  /** 種別別の試合数分布（bar）。byKind が空 / 全 0 なら空。 */
  function buildMatchesByKind(
    stats: TeamMatchStatsResponse,
    labels: TeamChartLabels,
  ): ChartData<'bar'> {
    const byKind = stats.byKind ?? []
    if (byKind.length === 0 || byKind.every((k) => (k.matches ?? 0) === 0)) {
      return { labels: [], datasets: [] }
    }
    return {
      labels: byKind.map((k) => labels.kind(k.kind ?? '')),
      datasets: [
        {
          label: labels.series.matchesByKind,
          data: byKind.map((k) => k.matches ?? 0),
          backgroundColor: withAlpha(OKABE_ITO.skyBlue, 0.7),
          borderColor: OKABE_ITO.skyBlue,
          borderWidth: 1,
        },
      ],
    }
  }

  return {
    buildResultSummary,
    buildPlayerGoalsRanking,
    buildRecentFormLine,
    buildMatchesByKind,
  }
}
