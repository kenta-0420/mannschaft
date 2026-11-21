import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import type { MatchEventResponse } from '~/types/match'
import { useMatchTimerCore, type TimerSportConfig } from './useMatchTimerCore'
import { SOCCER_TIMER_CONFIG } from './useMatchTimerSoccer'
import { FUTSAL_TIMER_CONFIG } from './useMatchTimerFutsal'
import { BASKETBALL_TIMER_CONFIG } from './useMatchTimerBasketball'

function event(
  sortSeq: number,
  period: MatchEventResponse['period'],
  eventType: MatchEventResponse['eventType'],
  minute?: number,
  stoppageMinute?: number,
): MatchEventResponse {
  return {
    id: `event-${sortSeq}`,
    sortSeq,
    period,
    eventType,
    minute,
    stoppageMinute,
  }
}

const scopes: ReturnType<typeof effectScope>[] = []
function timer(config: TimerSportConfig = SOCCER_TIMER_CONFIG) {
  const scope = effectScope()
  scopes.push(scope)
  const onPeriodTransition = vi.fn()
  return {
    ...scope.run(() => useMatchTimerCore(config, { onPeriodTransition }))!,
    onPeriodTransition,
  }
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => {
  scopes.splice(0).forEach((scope) => scope.stop())
  vi.useRealTimers()
})

describe('記録再開時のタイマー復元（CMP-261115-0946）', () => {
  it.each([
    [SOCCER_TIMER_CONFIG, 'FIRST_HALF', 17, '17:00'],
    [SOCCER_TIMER_CONFIG, 'SECOND_HALF', 58, '13:00'],
    [SOCCER_TIMER_CONFIG, 'EXTRA_FIRST', 96, '06:00'],
    [SOCCER_TIMER_CONFIG, 'EXTRA_SECOND', 113, '08:00'],
    [FUTSAL_TIMER_CONFIG, 'SECOND_HALF', 27, '07:00'],
    [BASKETBALL_TIMER_CONFIG, 'QUARTER_1', 4, '04:00'],
    [BASKETBALL_TIMER_CONFIG, 'QUARTER_2', 16, '06:00'],
    [BASKETBALL_TIMER_CONFIG, 'QUARTER_3', 26, '06:00'],
    [BASKETBALL_TIMER_CONFIG, 'QUARTER_4', 36, '06:00'],
    [BASKETBALL_TIMER_CONFIG, 'OVERTIME', 43, '03:00'],
  ] as const)('%sの%sは保存済み累計分から復元する', (config, period, minute, clock) => {
    const t = timer(config)

    t.restore({
      events: [event(1, period, 'PERIOD_START'), event(2, period, 'OTHER', minute)],
      matchStatus: 'IN_PROGRESS',
    })

    expect(t.state.value).toBe(period)
    expect(t.currentMinute.value).toBe(minute)
    expect(t.displayClock.value).toBe(clock)
    expect(t.isRunning.value).toBe(true)
    expect(t.onPeriodTransition).not.toHaveBeenCalled()
    vi.advanceTimersByTime(60000)
    expect(t.currentMinute.value).toBe(minute + 1)
  })

  it.each([
    [SOCCER_TIMER_CONFIG, 'FIRST_HALF', 'HALF_TIME'],
    [SOCCER_TIMER_CONFIG, 'SECOND_HALF', 'COMPLETED'],
    [BASKETBALL_TIMER_CONFIG, 'QUARTER_1', 'BREAK_1'],
    [BASKETBALL_TIMER_CONFIG, 'QUARTER_2', 'HALF_TIME_BREAK'],
    [BASKETBALL_TIMER_CONFIG, 'QUARTER_3', 'BREAK_3'],
    [BASKETBALL_TIMER_CONFIG, 'QUARTER_4', 'COMPLETED'],
  ] as const)('%sの%s終了後は%sで停止する', (config, period, state) => {
    const t = timer(config)

    t.restore({
      events: [event(1, period, 'PERIOD_END', 45)],
      matchStatus: 'IN_PROGRESS',
    })

    expect(t.state.value).toBe(state)
    expect(t.lastActivePeriod.value).toBe(period)
    expect(t.isRunning.value).toBe(false)
    vi.advanceTimersByTime(60000)
    expect(t.displayClock.value).toBe('00:00')
  })

  it('再訪そのものは境界をPOSTせず、次の操作も前半開始を重複しない', async () => {
    const t = timer()
    t.restore({
      events: [event(1, 'FIRST_HALF', 'PERIOD_START'), event(2, 'FIRST_HALF', 'OTHER', 17)],
      matchStatus: 'IN_PROGRESS',
    })
    expect(t.onPeriodTransition).not.toHaveBeenCalled()

    await t.advance()

    expect(t.onPeriodTransition).toHaveBeenCalledExactlyOnceWith({
      endingPeriod: 'FIRST_HALF',
      startingPeriod: null,
      minute: 17,
    })
    await t.advance()
    expect(t.onPeriodTransition).toHaveBeenLastCalledWith({
      endingPeriod: null,
      startingPeriod: 'SECOND_HALF',
      minute: 45,
    })
  })

  it('入力順やcreatedAtではなくsortSeqの境界を優先し、後追いの前半記録で巻き戻さない', () => {
    const t = timer()
    const events = [
      event(4, 'FIRST_HALF', 'OTHER', 5),
      event(3, 'SECOND_HALF', 'OTHER', 58),
      event(2, 'SECOND_HALF', 'PERIOD_START', 45),
      event(1, 'FIRST_HALF', 'PERIOD_END', 47),
    ]

    t.restore({ events, matchStatus: 'IN_PROGRESS' })

    expect(t.state.value).toBe('SECOND_HALF')
    expect(t.currentMinute.value).toBe(58)
  })

  it('複数回OTは最後のSTART以後だけを復元し、STARTが持つ旧period分を流用しない', () => {
    const t = timer(BASKETBALL_TIMER_CONFIG)
    t.restore({
      events: [
        event(1, 'OVERTIME', 'PERIOD_START', 40),
        event(2, 'OVERTIME', 'OTHER', 44),
        event(3, 'OVERTIME', 'PERIOD_END', 45),
        event(4, 'OVERTIME', 'PERIOD_START', 45),
      ],
      matchStatus: 'IN_PROGRESS',
    })
    expect(t.state.value).toBe('OVERTIME')
    expect(t.currentMinute.value).toBe(40)
    expect(t.displayClock.value).toBe('00:00')
  })

  it('境界のない旧記録もperiodと0分・追加分から復元する', () => {
    const t = timer()
    t.restore({
      events: [event(1, 'FIRST_HALF', 'OTHER', 0, 2)],
      matchStatus: 'IN_PROGRESS',
    })
    expect(t.state.value).toBe('FIRST_HALF')
    expect(t.currentMinute.value).toBe(0)
    expect(t.stoppageMinute.value).toBe(2)
  })

  it('PKは分概念がなく停止し、終了済みstatusは履歴より優先する', () => {
    const t = timer()
    const events = [event(1, 'PENALTY_SHOOTOUT', 'PERIOD_START', 120)]
    t.restore({ events, matchStatus: 'IN_PROGRESS' })
    expect(t.state.value).toBe('PENALTY_SHOOTOUT')
    expect(t.currentMinute.value).toBeNull()
    expect(t.isRunning.value).toBe(false)
    t.restore({ events, matchStatus: 'COMPLETED' })
    expect(t.state.value).toBe('COMPLETED')
    expect(t.lastActivePeriod.value).toBe('PENALTY_SHOOTOUT')
    expect(t.onPeriodTransition).not.toHaveBeenCalled()
  })

  it('0件はWAITING、終了済み0件はCOMPLETEDで停止する', () => {
    const t = timer()
    t.restore({ events: [], matchStatus: 'IN_PROGRESS' })
    expect(t.state.value).toBe('WAITING')
    expect(t.currentMinute.value).toBeNull()
    t.restore({ events: [], matchStatus: 'COMPLETED' })
    expect(t.state.value).toBe('COMPLETED')
    expect(t.isRunning.value).toBe(false)
  })

  it('延長前半ENDだけで延長後半START欠落なら開始済みと捏造しない', () => {
    const t = timer()
    expect(() =>
      t.restore({
        events: [event(1, 'EXTRA_FIRST', 'PERIOD_END', 105)],
        matchStatus: 'IN_PROGRESS',
      }),
    ).toThrow()
  })

  it('終了済みstatusは不完全な境界履歴にも優先して停止する', () => {
    const t = timer()

    t.restore({ events: [event(1, 'EXTRA_FIRST', 'PERIOD_END', 105)], matchStatus: 'COMPLETED' })

    expect(t.state.value).toBe('COMPLETED')
    expect(t.lastActivePeriod.value).toBe('EXTRA_FIRST')
    expect(t.isRunning.value).toBe(false)
    expect(t.onPeriodTransition).not.toHaveBeenCalled()
  })
})
