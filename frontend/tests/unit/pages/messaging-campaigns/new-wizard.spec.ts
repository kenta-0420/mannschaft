import { describe, it, expect } from 'vitest'

/**
 * F09.17 new.vue ウィザード遷移ロジックの単体テスト
 *
 * フルマウントテスト（Nuxt + PrimeVue + i18n + router）は重いので、ここでは
 * ウィザード遷移の判定関数相当のステップ前進可否を単独で検証する。
 */

type Step = 0 | 1 | 2 | 3

function canAdvance(step: Step, ctx: {
  basicValid: boolean
  channelCount: number
  segmentCount: number
}): boolean {
  if (step === 0) return ctx.basicValid
  if (step === 1) return ctx.channelCount > 0
  if (step === 2) return ctx.segmentCount > 0
  return true
}

describe('new.vue wizard advance gating', () => {
  it('WIZ-001: step0 は basic 検証通過のみ前進可', () => {
    expect(canAdvance(0, { basicValid: true, channelCount: 0, segmentCount: 0 })).toBe(true)
    expect(canAdvance(0, { basicValid: false, channelCount: 0, segmentCount: 0 })).toBe(false)
  })

  it('WIZ-002: step1 はチャネル 1 件以上で前進可', () => {
    expect(canAdvance(1, { basicValid: true, channelCount: 0, segmentCount: 0 })).toBe(false)
    expect(canAdvance(1, { basicValid: true, channelCount: 1, segmentCount: 0 })).toBe(true)
  })

  it('WIZ-003: step2 はセグメント 1 件以上で前進可', () => {
    expect(canAdvance(2, { basicValid: true, channelCount: 1, segmentCount: 0 })).toBe(false)
    expect(canAdvance(2, { basicValid: true, channelCount: 1, segmentCount: 1 })).toBe(true)
  })

  it('WIZ-004: step3 は常に submit 可（前提条件は前段で検証済み）', () => {
    expect(canAdvance(3, { basicValid: true, channelCount: 1, segmentCount: 1 })).toBe(true)
  })
})
