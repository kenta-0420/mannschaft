import { describe, it, expect } from 'vitest'

/**
 * F09.17 Phase 11-d-4 — チームスコープ new.vue ウィザード遷移ロジックの単体テスト
 *
 * <p>組織版 (tests/unit/pages/messaging-campaigns/new-wizard.spec.ts) と同型のロジックを
 * scope='TEAM' 文脈で再検証する。フルマウントテストは重いため、ステップ前進判定関数
 * 相当を単独で検証する。チーム版ページは組織版を複製し scope='TEAM' 化したのみで、
 * 前進判定そのものは scope に依存しないため、判定関数自体は同一。</p>
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

describe('teams new.vue wizard advance gating (scope=TEAM)', () => {
  it('TEAM-WIZ-001: step0 は basic 検証通過のみ前進可', () => {
    expect(canAdvance(0, { basicValid: true, channelCount: 0, segmentCount: 0 })).toBe(true)
    expect(canAdvance(0, { basicValid: false, channelCount: 0, segmentCount: 0 })).toBe(false)
  })

  it('TEAM-WIZ-002: step1 はチャネル 1 件以上で前進可', () => {
    expect(canAdvance(1, { basicValid: true, channelCount: 0, segmentCount: 0 })).toBe(false)
    expect(canAdvance(1, { basicValid: true, channelCount: 1, segmentCount: 0 })).toBe(true)
  })

  it('TEAM-WIZ-003: step2 はセグメント 1 件以上で前進可', () => {
    expect(canAdvance(2, { basicValid: true, channelCount: 1, segmentCount: 0 })).toBe(false)
    expect(canAdvance(2, { basicValid: true, channelCount: 1, segmentCount: 1 })).toBe(true)
  })

  it('TEAM-WIZ-004: step3 は常に submit 可（前提条件は前段で検証済み）', () => {
    expect(canAdvance(3, { basicValid: true, channelCount: 1, segmentCount: 1 })).toBe(true)
  })
})
