/**
 * F08.10 Phase 3-C — BaseChart.vue のユニットテスト。
 *
 * chart.js を vi.mock せず、実 chart.js を canvas の 2D コンテキストをスタブして動かす。
 * （chart.js を vi.mock すると他のチャートテストとモジュールモックが衝突して
 *   STACK_TRACE_ERROR / 呼び出し回数の相互汚染が起きるため、本ファイルは実描画パスを
 *   通し DOM のみをアサートする。色覚配慮や空状態の判定はラッパー側ロジックで決まる。）
 *
 * 検証内容（DOM ベース・ファイルローカルで決定的）:
 * - 描画可能データがあれば canvas が出る
 * - データ無し / 全 null は空状態（DashboardEmptyState メッセージ）を出し canvas は出さない
 */
import { describe, it, expect, beforeAll } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { ChartData } from 'chart.js'

// happy-dom には canvas 2D コンテキストが無いため最小スタブを注入する。
// 実 chart.js はこのコンテキストを使って描画する（描画結果は検証しない）。
beforeAll(() => {
  const proto = globalThis.HTMLCanvasElement?.prototype
  if (proto && typeof proto.getContext !== 'function') {
    Object.defineProperty(proto, 'getContext', {
      configurable: true,
      value: () =>
        new Proxy(
          {},
          {
            get: (_t, prop) => {
              if (prop === 'canvas') return { width: 0, height: 0 }
              if (prop === 'measureText') return () => ({ width: 0 })
              if (prop === 'getImageData') return () => ({ data: [] })
              if (prop === 'createLinearGradient' || prop === 'createRadialGradient') {
                return () => ({ addColorStop: () => {} })
              }
              return () => {}
            },
          },
        ),
    })
  }
})

// eslint-disable-next-line import/first -- スタブ注入後にインポートする
import BaseChart from '~/components/charts/BaseChart.vue'

const filledData: ChartData<'bar'> = {
  labels: ['A', 'B'],
  datasets: [{ label: 'goals', data: [1, 2] }],
}

const emptyData: ChartData<'bar'> = {
  labels: [],
  datasets: [],
}

const allNullData: ChartData<'line'> = {
  labels: ['A', 'B'],
  datasets: [{ label: 'goals', data: [null, null] }],
}

// ClientOnly は SSR スタブ環境では default スロットを描画しないため、
// テストでは default スロットをそのまま描画するスタブに差し替える
// （SSR 安全性は本番要件・ここでは client 描画後の DOM を検証する）。
const global = {
  stubs: {
    ClientOnly: { template: '<div><slot /></div>' },
  },
}

describe('BaseChart.vue', () => {
  it('BASE-CHART-001: 描画可能データがあると canvas が出る', async () => {
    const wrapper = await mountSuspended(BaseChart, {
      props: { type: 'bar', data: filledData },
      global,
    })
    expect(wrapper.find('canvas').exists()).toBe(true)
  })

  it('BASE-CHART-002: 空 datasets は空状態を出し canvas を出さない', async () => {
    const wrapper = await mountSuspended(BaseChart, {
      props: { type: 'bar', data: emptyData, emptyMessage: 'まだ記録なし' },
      global,
    })
    expect(wrapper.find('canvas').exists()).toBe(false)
    expect(wrapper.text()).toContain('まだ記録なし')
  })

  it('BASE-CHART-003: 全 null データも空状態とみなす（0/NaN を描かない）', async () => {
    const wrapper = await mountSuspended(BaseChart, {
      props: { type: 'line', data: allNullData },
      global,
    })
    expect(wrapper.find('canvas').exists()).toBe(false)
  })

  it('BASE-CHART-004: 空→有データに更新すると canvas が現れる', async () => {
    const wrapper = await mountSuspended(BaseChart, {
      props: { type: 'bar', data: emptyData },
      global,
    })
    expect(wrapper.find('canvas').exists()).toBe(false)
    await wrapper.setProps({ data: filledData })
    await nextTick()
    expect(wrapper.find('canvas').exists()).toBe(true)
  })
})
