/**
 * F08.10 サッカー用タイマー composable（CONTINUOUS_TIME・sports/01_soccer.md §8.5）。
 *
 * サッカーのピリオド遷移:
 *   WAITING → FIRST_HALF → HALF_TIME → SECOND_HALF
 *           → [EXTRA_FIRST → EXTRA_SECOND] → [PENALTY_SHOOTOUT] → COMPLETED
 *
 * 旧 useMatchTimer.ts の挙動をそのまま共通核（useMatchTimerCore）へ移植したもの（挙動不変）。
 * NEXT_STATE / PERIOD_MINUTE_OFFSET / running 判定 / stateToPeriod の値は旧実装と完全一致。
 */
import type { MatchPeriod } from '~/types/match'
import {
  useMatchTimerCore,
  nextIsCompleted,
  type TimerState,
  type TimerSportConfig,
  type UseMatchTimerCoreOptions,
} from '~/composables/match/sport/useMatchTimerCore'

/**
 * 各ピリオドの「経過分の起算オフセット（分）」。
 * サッカー: 前半 0・後半 45・延長前半 90・延長後半 105 起算（標準的な前後半 45 分）。
 */
const SOCCER_OFFSET: Partial<Record<TimerState, number>> = {
  WAITING: 0,
  FIRST_HALF: 0,
  HALF_TIME: 45,
  SECOND_HALF: 45,
  EXTRA_FIRST: 90,
  EXTRA_SECOND: 105,
  PENALTY_SHOOTOUT: 120,
  COMPLETED: 120,
}

/** 各状態から「次に進む」状態（延長/PK は明示分岐 goExtra/goPenaltyShootout で飛ぶ）。 */
const SOCCER_NEXT: Partial<Record<TimerState, TimerState | null>> = {
  WAITING: 'FIRST_HALF',
  FIRST_HALF: 'HALF_TIME',
  HALF_TIME: 'SECOND_HALF',
  SECOND_HALF: 'COMPLETED', // 延長なしが標準
  EXTRA_FIRST: 'EXTRA_SECOND',
  EXTRA_SECOND: 'COMPLETED', // PK へは goPenaltyShootout で分岐
  PENALTY_SHOOTOUT: 'COMPLETED',
  COMPLETED: null,
}

/** サッカー（前後半）のタイマー設定。フットサルも本設定（オフセット差し替え）を流用する。 */
export const SOCCER_TIMER_CONFIG: TimerSportConfig = {
  sport: 'SOCCER',
  initialState: 'WAITING',
  nextState: SOCCER_NEXT,
  periodMinuteOffset: SOCCER_OFFSET,
  isRunningState(state) {
    return (
      state === 'FIRST_HALF' ||
      state === 'SECOND_HALF' ||
      state === 'EXTRA_FIRST' ||
      state === 'EXTRA_SECOND'
    )
  },
  stateToPeriod(state): MatchPeriod | null {
    switch (state) {
      case 'FIRST_HALF':
        return 'FIRST_HALF'
      case 'SECOND_HALF':
        return 'SECOND_HALF'
      case 'EXTRA_FIRST':
        return 'EXTRA_FIRST'
      case 'EXTRA_SECOND':
        return 'EXTRA_SECOND'
      case 'PENALTY_SHOOTOUT':
        return 'PENALTY_SHOOTOUT'
      default:
        return null // WAITING / HALF_TIME / COMPLETED
    }
  },
  isMinuteless(state) {
    // WAITING と PK 戦（分概念なし）は minute を出さない。
    return state === 'WAITING' || state === 'PENALTY_SHOOTOUT'
  },
}

/** サッカー用タイマー。前後半・延長・PK の明示分岐を備える（旧 API 互換）。 */
export function useMatchTimerSoccer(options: UseMatchTimerCoreOptions = {}) {
  const core = useMatchTimerCore(SOCCER_TIMER_CONFIG, options)

  /** 後半終了後に延長へ進む（SECOND_HALF からのみ）。 */
  async function goExtra(): Promise<void> {
    await core.goTo('EXTRA_FIRST', 'SECOND_HALF')
  }
  /** PK 戦へ進む（EXTRA_SECOND / SECOND_HALF からのみ）。 */
  async function goPenaltyShootout(): Promise<void> {
    await core.goTo('PENALTY_SHOOTOUT', ['EXTRA_SECOND', 'SECOND_HALF'])
  }

  return { ...core, goExtra, goPenaltyShootout }
}

/** サッカー設定で「次状態が COMPLETED か」（live.vue の handleAdvance 判定に使う）。 */
export function isNextCompletedSoccer(state: TimerState): boolean {
  return (
    (state === 'EXTRA_SECOND' || state === 'PENALTY_SHOOTOUT') &&
    nextIsCompleted(SOCCER_TIMER_CONFIG, state)
  )
}
