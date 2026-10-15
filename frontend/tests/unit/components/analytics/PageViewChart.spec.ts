/**
 * F10.8 — PageViewChart.vue のユニットテスト（根治: Chart.js Controller 未登録バグ）。
 *
 * <p>実機E2Eで「"line" is not a registered controller」というコンソールエラーが
 * 実証された。原因は Chart.js v4 の tree-shaking 仕様で、使用する Controller
 * （LineController / BarController）を明示的に {@code Chart.register()} していなかったこと
 * （chart.js の `Chart.registry.getController(type)` は未登録の Controller に対して
 * まさにこのメッセージで throw する）。</p>
 *
 * <p>Vue の `<script setup>` はトップレベルのコードを `setup()` 関数本体へ丸ごと
 * 移送するため、`ChartJS.register(...)` の副作用はコンポーネントの import 時点ではなく
 * **インスタンス化（マウント）時**に初めて実行される。そのため単純な import では
 * 検知できず、mountSuspended でマウントしてから chart.js の実 registry を検証する。</p>
 *
 * <p>jsdom/happy-dom は 'canvas' npm package 無しでは Canvas 2D コンテキストを提供できず、
 * Chart インスタンスの生成自体は「can't acquire context」という環境起因の別エラーで
 * 失敗しうる。だが `ChartJS.register(...)` は Chart インスタンス生成より前の setup() 本体で
 * 同期的に実行されるため、registry の状態検証はこの環境制約に左右されない
 * （BASE-CHART / AD-REPORT-CHART の chart.js 全体モックとは異なり、register 漏れという
 * 本バグの根本原因を直接検知できる）。</p>
 */
import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { Chart as ChartJS } from 'chart.js'
import type { DailyPageView, MonthlyPageView } from '~/types/analytics'
import PageViewChart from '~/components/analytics/PageViewChart.vue'

const daily: DailyPageView[] = [
  { date: '2026-07-01', views: 10, uniqueVisitors: 5 },
  { date: '2026-07-02', views: 20, uniqueVisitors: 8 },
]

const monthly: MonthlyPageView[] = [
  { month: '2026-05', views: 100, uniqueVisitors: 40 },
  { month: '2026-06', views: 150, uniqueVisitors: 60 },
]

describe('PageViewChart.vue', () => {
  it('PAGE-VIEW-CHART-001: マウントすると日次表示（line）用の Controller が登録される', async () => {
    await mountSuspended(PageViewChart, { props: { daily, monthly } })
    expect(() => ChartJS.registry.getController('line')).not.toThrow()
  })

  it('PAGE-VIEW-CHART-002: マウントすると月次表示（bar）用の Controller が登録される', async () => {
    await mountSuspended(PageViewChart, { props: { daily, monthly } })
    expect(() => ChartJS.registry.getController('bar')).not.toThrow()
  })

  it('PAGE-VIEW-CHART-003: line/bar が要する Scale・Element・Plugin も登録される', async () => {
    await mountSuspended(PageViewChart, { props: { daily, monthly } })
    expect(() => ChartJS.registry.getScale('category')).not.toThrow()
    expect(() => ChartJS.registry.getScale('linear')).not.toThrow()
    expect(() => ChartJS.registry.getElement('point')).not.toThrow()
    expect(() => ChartJS.registry.getElement('line')).not.toThrow()
    expect(() => ChartJS.registry.getElement('bar')).not.toThrow()
    expect(() => ChartJS.registry.getPlugin('legend')).not.toThrow()
    expect(() => ChartJS.registry.getPlugin('tooltip')).not.toThrow()
  })
})
