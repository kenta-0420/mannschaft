/**
 * F08.10 Phase 3-C — useMatchAnalyticsCharts のユニットテスト。
 *
 * 個人キャリア統計（UserMatchStatsResponse）→ chart.js ChartData への
 * 整形ロジックを検証する。chart.js 描画は介さず純データ整形のみをアサートする。
 * - 空データ（totalMatches=0 / trend 空 / byKind 空）は空 datasets を返す（§G.8 空状態）
 * - goalsPer90 の null は「—」フォールバック（§G.8）
 * - line は得点・出場時間の 2 系列、doughnut/bar は kind 別
 */
import { describe, it, expect } from 'vitest'
import { useMatchAnalyticsCharts } from '~/composables/match/useMatchAnalyticsCharts'
import type { UserMatchStatsResponse } from '~/types/match'
import type { ChartLabels } from '~/composables/match/useMatchAnalyticsCharts'

const labels: ChartLabels = {
  kind: (k) => `kind:${k}`,
  axis: { goals: 'G', assists: 'A', matches: 'M', minutes: 'Min', starterRate: 'SR' },
  series: { goals: 'goals', minutes: 'minutes', matchShare: 'share', goalsByKind: 'gbk' },
  month: (m) => m,
}

const fullStats: UserMatchStatsResponse = {
  userId: 1,
  totalMatches: 10,
  totalMinutes: 800,
  goals: 6,
  assists: 4,
  ownGoals: 0,
  yellowCards: 2,
  redCards: 0,
  starterMatches: 8,
  starterRate: 0.8,
  avgMinutes: 80,
  goalsPer90: 0.675,
  monthlyTrend: [
    { month: '2026-04', matches: 4, minutes: 320, goals: 3, assists: 2 },
    { month: '2026-05', matches: 6, minutes: 480, goals: 3, assists: 2 },
  ],
  seasonTrend: [],
  byKind: [
    { kind: 'LEAGUE', matches: 6, minutes: 480, goals: 4, assists: 3 },
    { kind: 'FRIENDLY', matches: 4, minutes: 320, goals: 2, assists: 1 },
  ],
}

const emptyStats: UserMatchStatsResponse = {
  userId: 1,
  totalMatches: 0,
  totalMinutes: 0,
  goals: 0,
  assists: 0,
  monthlyTrend: [],
  seasonTrend: [],
  byKind: [],
}

describe('useMatchAnalyticsCharts', () => {
  const charts = useMatchAnalyticsCharts()

  it('MAC-001: radar は試合があれば 5 軸 1 系列を返す', () => {
    const data = charts.buildRadar(fullStats, labels)
    expect(data.labels).toHaveLength(5)
    expect(data.datasets).toHaveLength(1)
    expect(data.datasets[0]!.data).toHaveLength(5)
    // 正規化値は 0..100 の範囲に収まる
    for (const v of data.datasets[0]!.data as number[]) {
      expect(v).toBeGreaterThanOrEqual(0)
      expect(v).toBeLessThanOrEqual(100)
    }
  })

  it('MAC-002: 試合 0 件なら radar は空 datasets（空状態）', () => {
    const data = charts.buildRadar(emptyStats, labels)
    expect(data.datasets).toHaveLength(0)
  })

  it('MAC-003: line は得点・出場時間の 2 系列を月数ぶん持つ', () => {
    const data = charts.buildMonthlyLine(fullStats, labels)
    expect(data.labels).toEqual(['2026-04', '2026-05'])
    expect(data.datasets).toHaveLength(2)
    expect(data.datasets[0]!.data).toEqual([3, 3])
    expect(data.datasets[1]!.data).toEqual([320, 480])
  })

  it('MAC-004: trend が空なら line は空（空状態）', () => {
    const data = charts.buildMonthlyLine(emptyStats, labels)
    expect(data.datasets).toHaveLength(0)
    expect(data.labels).toHaveLength(0)
  })

  it('MAC-005: doughnut は matches>0 の kind だけを割合として返す', () => {
    const data = charts.buildKindShare(fullStats, labels)
    expect(data.labels).toEqual(['kind:LEAGUE', 'kind:FRIENDLY'])
    expect(data.datasets[0]!.data).toEqual([6, 4])
    // 色覚配慮パレットがセグメント数ぶん割り当てられる
    expect((data.datasets[0]!.backgroundColor as string[]).length).toBe(2)
  })

  it('MAC-006: bar は kind 別の得点を返す', () => {
    const data = charts.buildGoalsByKind(fullStats, labels)
    expect(data.datasets[0]!.data).toEqual([4, 2])
  })

  it('MAC-007: 得点が全 0 の byKind なら bar は空（空状態）', () => {
    const stats: UserMatchStatsResponse = {
      ...fullStats,
      byKind: [{ kind: 'LEAGUE', matches: 3, goals: 0 }],
    }
    const data = charts.buildGoalsByKind(stats, labels)
    expect(data.datasets).toHaveLength(0)
  })

  it('MAC-008: goalsPer90 が null なら「—」、数値なら 2 桁表示', () => {
    expect(charts.formatGoalsPer90(null)).toBe('—')
    expect(charts.formatGoalsPer90(undefined)).toBe('—')
    expect(charts.formatGoalsPer90(0.675)).toBe('0.68')
    expect(charts.formatGoalsPer90(0)).toBe('0.00')
  })

  it('MAC-009: 自己ベストは monthlyTrend の月別ピークを返す', () => {
    const best = charts.computePersonalBest(fullStats)
    expect(best.topGoals).toBe(3)
    expect(best.longestMinutes).toBe(480)
  })

  it('MAC-010: trend が空でも自己ベストは 0/0 で安全', () => {
    const best = charts.computePersonalBest(emptyStats)
    expect(best).toEqual({ topGoals: 0, longestMinutes: 0 })
  })
})
