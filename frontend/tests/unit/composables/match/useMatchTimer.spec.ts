import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { effectScope } from 'vue'
import {
  useMatchTimer,
  isRunningState,
  stateToPeriod,
  type PeriodTransition,
  type TimerState,
} from '~/composables/match/useMatchTimer'

/**
 * F08.10 useMatchTimer ユニットテスト（タイマー状態機械・sports/01_soccer.md §8.5）。
 *
 * 観点:
 *   TIMER-001: 初期は WAITING（停止・minute null）
 *   TIMER-002: advance で WAITING→FIRST_HALF→HALF_TIME→SECOND_HALF→COMPLETED と遷移
 *   TIMER-003: ピリオド切替で PERIOD_END/PERIOD_START のフックが順に発火する
 *   TIMER-004: 動作状態でのみタイマーが進む（経過分→minute 自動補完）
 *   TIMER-005: minute 手動訂正が自動補完を上書きする
 *   TIMER-006: 後半→延長→PK 戦の分岐遷移
 *   TIMER-007: isRunningState / stateToPeriod のヘルパ
 */

beforeEach(() => {
  vi.useFakeTimers()
})
afterEach(() => {
  vi.useRealTimers()
})

describe('useMatchTimer ヘルパ', () => {
  it('TIMER-007: isRunningState は動作状態で true', () => {
    expect(isRunningState('FIRST_HALF')).toBe(true)
    expect(isRunningState('SECOND_HALF')).toBe(true)
    expect(isRunningState('WAITING')).toBe(false)
    expect(isRunningState('HALF_TIME')).toBe(false)
    expect(isRunningState('PENALTY_SHOOTOUT')).toBe(false)
    expect(isRunningState('COMPLETED')).toBe(false)
  })

  it('TIMER-007: stateToPeriod は動作/PK 状態を MatchPeriod に、停止状態を null に', () => {
    expect(stateToPeriod('FIRST_HALF')).toBe('FIRST_HALF')
    expect(stateToPeriod('EXTRA_FIRST')).toBe('EXTRA_FIRST')
    expect(stateToPeriod('PENALTY_SHOOTOUT')).toBe('PENALTY_SHOOTOUT')
    expect(stateToPeriod('WAITING')).toBeNull()
    expect(stateToPeriod('HALF_TIME')).toBeNull()
    expect(stateToPeriod('COMPLETED')).toBeNull()
  })
})

describe('useMatchTimer', () => {
  it('TIMER-001: 初期は WAITING（停止・minute null）', () => {
    const scope = effectScope()
    scope.run(() => {
      const timer = useMatchTimer()
      expect(timer.state.value).toBe('WAITING')
      expect(timer.isRunning.value).toBe(false)
      expect(timer.currentMinute.value).toBeNull()
      expect(timer.displayClock.value).toBe('00:00')
    })
    scope.stop()
  })

  it('TIMER-002: advance で標準遷移する', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const timer = useMatchTimer()
      const seq: TimerState[] = []
      await timer.advance(); seq.push(timer.state.value)
      await timer.advance(); seq.push(timer.state.value)
      await timer.advance(); seq.push(timer.state.value)
      await timer.advance(); seq.push(timer.state.value)
      expect(seq).toEqual(['FIRST_HALF', 'HALF_TIME', 'SECOND_HALF', 'COMPLETED'])
    })
    scope.stop()
  })

  it('TIMER-003: ピリオド切替で PERIOD_END→PERIOD_START フックが発火する', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const events: PeriodTransition[] = []
      const timer = useMatchTimer({ onPeriodTransition: (t) => { events.push(t) } })
      await timer.advance() // WAITING→FIRST_HALF（end=null, start=FIRST_HALF）
      await timer.advance() // FIRST_HALF→HALF_TIME（end=FIRST_HALF, start=null）
      await timer.advance() // HALF_TIME→SECOND_HALF（end=null, start=SECOND_HALF）
      expect(events[0]).toMatchObject({ endingPeriod: null, startingPeriod: 'FIRST_HALF' })
      expect(events[1]).toMatchObject({ endingPeriod: 'FIRST_HALF', startingPeriod: null })
      expect(events[2]).toMatchObject({ endingPeriod: null, startingPeriod: 'SECOND_HALF' })
    })
    scope.stop()
  })

  it('TIMER-004: 動作状態で経過分が minute に自動補完される', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const timer = useMatchTimer()
      await timer.advance() // FIRST_HALF
      vi.advanceTimersByTime(125_000) // 2 分 5 秒
      expect(timer.elapsedMinutesInPeriod.value).toBe(2)
      expect(timer.currentMinute.value).toBe(2) // 前半オフセット 0 + 2
      await timer.advance() // HALF_TIME（経過リセット）
      await timer.advance() // SECOND_HALF（オフセット 45）
      vi.advanceTimersByTime(60_000) // 1 分
      expect(timer.currentMinute.value).toBe(46) // 45 + 1
    })
    scope.stop()
  })

  it('TIMER-005: minute 手動訂正が自動補完を上書きし、null で戻る', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const timer = useMatchTimer()
      await timer.advance() // FIRST_HALF
      vi.advanceTimersByTime(120_000) // 2 分
      expect(timer.currentMinute.value).toBe(2)
      timer.overrideMinute(40)
      expect(timer.currentMinute.value).toBe(40)
      timer.overrideMinute(null)
      expect(timer.currentMinute.value).toBe(2)
    })
    scope.stop()
  })

  it('TIMER-006: 後半→延長→PK 戦の分岐遷移', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const timer = useMatchTimer()
      await timer.advance() // FIRST_HALF
      await timer.advance() // HALF_TIME
      await timer.advance() // SECOND_HALF
      await timer.goExtra()
      expect(timer.state.value).toBe('EXTRA_FIRST')
      await timer.advance()
      expect(timer.state.value).toBe('EXTRA_SECOND')
      await timer.goPenaltyShootout()
      expect(timer.state.value).toBe('PENALTY_SHOOTOUT')
      expect(timer.isRunning.value).toBe(false) // PK は分概念なし＝停止
      expect(timer.currentMinute.value).toBeNull()
      await timer.complete()
      expect(timer.state.value).toBe('COMPLETED')
    })
    scope.stop()
  })

  it('TIMER-006b: goExtra は SECOND_HALF 以外では無視される', async () => {
    const scope = effectScope()
    await scope.run(async () => {
      const timer = useMatchTimer()
      await timer.goExtra() // WAITING のままのはず
      expect(timer.state.value).toBe('WAITING')
    })
    scope.stop()
  })
})
