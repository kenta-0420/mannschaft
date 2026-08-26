import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { effectScope } from 'vue'
import {
  useMatchTimerSoccer,
  isNextCompletedSoccer,
  SOCCER_TIMER_CONFIG,
} from '~/composables/match/sport/useMatchTimerSoccer'
import {
  useMatchTimerFutsal,
  isNextCompletedFutsal,
  FUTSAL_TIMER_CONFIG,
} from '~/composables/match/sport/useMatchTimerFutsal'
import {
  useMatchTimerBasketball,
  isNextCompletedBasketball,
  BASKETBALL_TIMER_CONFIG,
} from '~/composables/match/sport/useMatchTimerBasketball'
import type { TimerState } from '~/composables/match/sport/useMatchTimerCore'

/**
 * F08.10 6-②b 競技別タイマー UT（04 §G.16・sports/01〜03 §8.5）。
 *
 * 観点:
 *   SOCCER:  移行で挙動不変（前後半→延長→PK・オフセット 0/45/90/105）
 *   FUTSAL:  前後半 20 分（オフセット 0/20/40/50）・状態遷移はサッカーと同形
 *   BASKET:  4 クォーター＋OT 遷移（WAITING→Q1→BREAK→Q2→HALF→Q3→BREAK→Q4→[OT]→COMPLETED）
 *   共通:    next が COMPLETED になる遷移の判定（handleAdvance の分岐根拠）
 */

beforeEach(() => {
  vi.useFakeTimers()
})
afterEach(() => {
  vi.useRealTimers()
})

function withScope<T>(fn: () => T | Promise<T>): Promise<T> {
  const scope = effectScope()
  const r = scope.run(fn) as T | Promise<T>
  return Promise.resolve(r).finally(() => scope.stop())
}

describe('useMatchTimerSoccer（移行で挙動不変）', () => {
  it('SOCCER-001: WAITING→FIRST_HALF→HALF_TIME→SECOND_HALF→COMPLETED', async () => {
    await withScope(async () => {
      const t = useMatchTimerSoccer()
      const seq: TimerState[] = []
      expect(t.state.value).toBe('WAITING')
      await t.advance(); seq.push(t.state.value)
      await t.advance(); seq.push(t.state.value)
      await t.advance(); seq.push(t.state.value)
      await t.advance(); seq.push(t.state.value)
      expect(seq).toEqual(['FIRST_HALF', 'HALF_TIME', 'SECOND_HALF', 'COMPLETED'])
    })
  })

  it('SOCCER-002: 前半 0 起算・後半 45 起算（オフセット不変）', async () => {
    await withScope(async () => {
      const t = useMatchTimerSoccer()
      await t.advance() // FIRST_HALF
      vi.advanceTimersByTime(125_000) // 2分5秒
      expect(t.currentMinute.value).toBe(2)
      await t.advance() // HALF_TIME
      await t.advance() // SECOND_HALF（45 起算）
      vi.advanceTimersByTime(60_000)
      expect(t.currentMinute.value).toBe(46)
    })
  })

  it('SOCCER-003: 後半→延長→PK の明示分岐（PK は分概念なし）', async () => {
    await withScope(async () => {
      const t = useMatchTimerSoccer()
      await t.advance() // FIRST_HALF
      await t.advance() // HALF_TIME
      await t.advance() // SECOND_HALF
      await t.goExtra()
      expect(t.state.value).toBe('EXTRA_FIRST')
      await t.advance() // EXTRA_SECOND
      await t.goPenaltyShootout()
      expect(t.state.value).toBe('PENALTY_SHOOTOUT')
      expect(t.isRunning.value).toBe(false)
      expect(t.currentMinute.value).toBeNull()
    })
  })

  it('SOCCER-004: isNextCompletedSoccer は EXTRA_SECOND / PENALTY_SHOOTOUT のみ true', () => {
    expect(isNextCompletedSoccer('EXTRA_SECOND')).toBe(true)
    expect(isNextCompletedSoccer('PENALTY_SHOOTOUT')).toBe(true)
    expect(isNextCompletedSoccer('SECOND_HALF')).toBe(false)
    expect(isNextCompletedSoccer('WAITING')).toBe(false)
  })

  it('SOCCER-005: stateToPeriod / config の写像が旧実装と一致', () => {
    expect(SOCCER_TIMER_CONFIG.stateToPeriod('FIRST_HALF')).toBe('FIRST_HALF')
    expect(SOCCER_TIMER_CONFIG.stateToPeriod('PENALTY_SHOOTOUT')).toBe('PENALTY_SHOOTOUT')
    expect(SOCCER_TIMER_CONFIG.stateToPeriod('HALF_TIME')).toBeNull()
  })
})

describe('useMatchTimerFutsal（前後半 20 分）', () => {
  it('FUTSAL-001: 状態遷移はサッカーと同形（前後半→COMPLETED）', async () => {
    await withScope(async () => {
      const t = useMatchTimerFutsal()
      const seq: TimerState[] = []
      await t.advance(); seq.push(t.state.value)
      await t.advance(); seq.push(t.state.value)
      await t.advance(); seq.push(t.state.value)
      await t.advance(); seq.push(t.state.value)
      expect(seq).toEqual(['FIRST_HALF', 'HALF_TIME', 'SECOND_HALF', 'COMPLETED'])
    })
  })

  it('FUTSAL-002: 後半オフセットが 20（サッカー 45 と異なる）', async () => {
    await withScope(async () => {
      const t = useMatchTimerFutsal()
      await t.advance() // FIRST_HALF
      vi.advanceTimersByTime(180_000) // 3分
      expect(t.currentMinute.value).toBe(3)
      await t.advance() // HALF_TIME
      await t.advance() // SECOND_HALF（20 起算）
      vi.advanceTimersByTime(120_000) // 2分
      expect(t.currentMinute.value).toBe(22)
    })
  })

  it('FUTSAL-003: 延長・PK の明示分岐を備える', async () => {
    await withScope(async () => {
      const t = useMatchTimerFutsal()
      await t.advance() // FIRST_HALF
      await t.advance() // HALF_TIME
      await t.advance() // SECOND_HALF
      await t.goExtra()
      expect(t.state.value).toBe('EXTRA_FIRST')
      await t.advance() // EXTRA_SECOND
      await t.goPenaltyShootout()
      expect(t.state.value).toBe('PENALTY_SHOOTOUT')
    })
  })

  it('FUTSAL-004: isNextCompletedFutsal は EXTRA_SECOND / PK のみ true', () => {
    expect(isNextCompletedFutsal('EXTRA_SECOND')).toBe(true)
    expect(isNextCompletedFutsal('PENALTY_SHOOTOUT')).toBe(true)
    expect(isNextCompletedFutsal('SECOND_HALF')).toBe(false)
  })

  it('FUTSAL-005: period 写像はサッカーと共有（前後半・延長・PK）', () => {
    expect(FUTSAL_TIMER_CONFIG.stateToPeriod('SECOND_HALF')).toBe('SECOND_HALF')
    expect(FUTSAL_TIMER_CONFIG.stateToPeriod('HALF_TIME')).toBeNull()
  })
})

describe('useMatchTimerBasketball（4 クォーター＋OT）', () => {
  it('BASKET-001: WAITING→Q1→BREAK→Q2→HALF→Q3→BREAK→Q4→COMPLETED', async () => {
    await withScope(async () => {
      const t = useMatchTimerBasketball()
      const seq: TimerState[] = []
      expect(t.state.value).toBe('WAITING')
      for (let i = 0; i < 8; i++) {
        await t.advance()
        seq.push(t.state.value)
      }
      expect(seq).toEqual([
        'QUARTER_1',
        'BREAK_1',
        'QUARTER_2',
        'HALF_TIME_BREAK',
        'QUARTER_3',
        'BREAK_3',
        'QUARTER_4',
        'COMPLETED',
      ])
    })
  })

  it('BASKET-002: クォーターのオフセット（Q1=0/Q2=10/Q3=20/Q4=30）', async () => {
    await withScope(async () => {
      const t = useMatchTimerBasketball()
      await t.advance() // Q1
      vi.advanceTimersByTime(120_000) // 2分
      expect(t.currentMinute.value).toBe(2)
      await t.advance() // BREAK_1（停止・分概念なし）
      expect(t.isRunning.value).toBe(false)
      expect(t.currentMinute.value).toBeNull()
      await t.advance() // Q2（10 起算）
      vi.advanceTimersByTime(60_000)
      expect(t.currentMinute.value).toBe(11)
    })
  })

  it('BASKET-003: Q4 で同点→OT 投入（goOvertime）→COMPLETED', async () => {
    await withScope(async () => {
      const t = useMatchTimerBasketball()
      for (let i = 0; i < 7; i++) await t.advance() // → QUARTER_4
      expect(t.state.value).toBe('QUARTER_4')
      await t.goOvertime()
      expect(t.state.value).toBe('OVERTIME')
      expect(t.isRunning.value).toBe(true)
      vi.advanceTimersByTime(60_000)
      expect(t.currentMinute.value).toBe(41) // OT 40 起算 + 1
      await t.complete()
      expect(t.state.value).toBe('COMPLETED')
    })
  })

  it('BASKET-004: OVERTIME からの再 OT（複数回 OT・同一 OVERTIME 値）', async () => {
    await withScope(async () => {
      const t = useMatchTimerBasketball()
      for (let i = 0; i < 7; i++) await t.advance() // QUARTER_4
      await t.goOvertime() // OVERTIME #1
      expect(t.state.value).toBe('OVERTIME')
      await t.goOvertime() // OVERTIME #2（再投入・同一値）
      expect(t.state.value).toBe('OVERTIME')
    })
  })

  it('BASKET-005: goOvertime は Q4 / OVERTIME 以外では無視される', async () => {
    await withScope(async () => {
      const t = useMatchTimerBasketball()
      await t.advance() // Q1
      await t.goOvertime()
      expect(t.state.value).toBe('QUARTER_1')
    })
  })

  it('BASKET-006: isNextCompletedBasketball は QUARTER_4 / OVERTIME のみ true', () => {
    expect(isNextCompletedBasketball('QUARTER_4')).toBe(true)
    expect(isNextCompletedBasketball('OVERTIME')).toBe(true)
    expect(isNextCompletedBasketball('QUARTER_3')).toBe(false)
    expect(isNextCompletedBasketball('WAITING')).toBe(false)
  })

  it('BASKET-007: period 写像（QUARTER_*/OVERTIME・停止は null）', () => {
    expect(BASKETBALL_TIMER_CONFIG.stateToPeriod('QUARTER_1')).toBe('QUARTER_1')
    expect(BASKETBALL_TIMER_CONFIG.stateToPeriod('OVERTIME')).toBe('OVERTIME')
    expect(BASKETBALL_TIMER_CONFIG.stateToPeriod('BREAK_1')).toBeNull()
    expect(BASKETBALL_TIMER_CONFIG.stateToPeriod('HALF_TIME_BREAK')).toBeNull()
  })

  it('BASKET-008: 停止状態でも lastActivePeriod は直近の進行 Q を保持', async () => {
    await withScope(async () => {
      const t = useMatchTimerBasketball()
      await t.advance() // Q1
      expect(t.lastActivePeriod.value).toBe('QUARTER_1')
      await t.advance() // BREAK_1（停止）でも Q1 を保持
      expect(t.lastActivePeriod.value).toBe('QUARTER_1')
    })
  })
})
