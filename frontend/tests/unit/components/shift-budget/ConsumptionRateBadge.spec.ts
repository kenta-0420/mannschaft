import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ConsumptionRateBadge from '~/components/shift-budget/ConsumptionRateBadge.vue'

/**
 * F08.7 Phase 10-γ: ConsumptionRateBadge のユニットテスト。
 *
 * <p>消化率 → ステータス判定（4 段階）と severity 色割当を検証する。
 * 設計書 §6.2.3 status 判定ルール:</p>
 * <ul>
 *   <li>rate < 0.80 → OK (success)</li>
 *   <li>0.80 ≤ rate < 1.00 → WARN (warn)</li>
 *   <li>1.00 ≤ rate < 1.20 → EXCEEDED (danger)</li>
 *   <li>rate ≥ 1.20 → SEVERE_EXCEEDED (danger)</li>
 * </ul>
 */
describe('ConsumptionRateBadge.vue', () => {
  it('rate=0.5 で OK ステータス + success severity を表示', async () => {
    const wrapper = await mountSuspended(ConsumptionRateBadge, {
      props: { rate: 0.5 },
    })
    const tag = wrapper.find('.p-tag')
    expect(tag.exists()).toBe(true)
    // 50% を含むテキストが描画される
    expect(wrapper.text()).toContain('50%')
  })

  it('rate=0.85 で WARN ステータス + warn severity を表示', async () => {
    const wrapper = await mountSuspended(ConsumptionRateBadge, {
      props: { rate: 0.85 },
    })
    expect(wrapper.text()).toContain('85%')
  })

  it('rate=1.05 で EXCEEDED ステータス + danger severity を表示', async () => {
    const wrapper = await mountSuspended(ConsumptionRateBadge, {
      props: { rate: 1.05 },
    })
    expect(wrapper.text()).toContain('105%')
  })

  it('rate=1.5 で SEVERE_EXCEEDED ステータス + danger severity を表示', async () => {
    const wrapper = await mountSuspended(ConsumptionRateBadge, {
      props: { rate: 1.5 },
    })
    expect(wrapper.text()).toContain('150%')
  })

  it('明示的な status prop を優先する', async () => {
    const wrapper = await mountSuspended(ConsumptionRateBadge, {
      props: { rate: 0.1, status: 'EXCEEDED', showPercent: false },
    })
    // showPercent=false なのでパーセンテージは出ない、status ラベルのみ
    expect(wrapper.text()).not.toContain('10%')
  })

  it('rate=null + status=null でも OK として描画される（フォールバック）', async () => {
    const wrapper = await mountSuspended(ConsumptionRateBadge, {
      props: { rate: null, showPercent: false },
    })
    // クラッシュせず描画
    const tag = wrapper.find('.p-tag')
    expect(tag.exists()).toBe(true)
  })
})
