/**
 * F08.10 フットサル用タイマー composable（CONTINUOUS_TIME・sports/02_futsal.md §8.5）。
 *
 * フットサルはサッカーと同じ前後半系の状態遷移を持ち、**ピリオド長が 20 分**である点だけが
 * 異なる（後半起算オフセットが 20・延長起算が 40/50）。設計書 §8.5 は
 * 「FE composable は useMatchTimerSoccer を再利用可（MVP は流用で確定）」としつつ
 * 「累積ファウル表示等で派生の余地を残す」とある。本波では **薄い派生 composable** として
 * オフセットのみ差し替えた設定を持ち、将来のフットサル固有拡張点を一箇所に集約する
 * （状態遷移・分岐・running 判定はサッカー設定を共有＝重複実装しない）。
 */
import type { MatchPeriod } from '~/types/match'
import {
  useMatchTimerCore,
  nextIsCompleted,
  type TimerState,
  type TimerSportConfig,
  type UseMatchTimerCoreOptions,
} from '~/composables/match/sport/useMatchTimerCore'
import { SOCCER_TIMER_CONFIG } from '~/composables/match/sport/useMatchTimerSoccer'

/**
 * フットサルの minute 起算オフセット（前後半 20 分）。
 * 前半 0・後半 20・延長前半 40・延長後半 50（延長は大会レギュレーション次第・§3）。
 */
const FUTSAL_OFFSET: Partial<Record<TimerState, number>> = {
  WAITING: 0,
  FIRST_HALF: 0,
  HALF_TIME: 20,
  SECOND_HALF: 20,
  EXTRA_FIRST: 40,
  EXTRA_SECOND: 50,
  PENALTY_SHOOTOUT: 60,
  COMPLETED: 60,
}

/** フットサル設定（サッカー設定の状態遷移・running 判定を共有し、オフセットのみ差し替え）。 */
export const FUTSAL_TIMER_CONFIG: TimerSportConfig = {
  ...SOCCER_TIMER_CONFIG,
  sport: 'FUTSAL',
  periodMinuteOffset: FUTSAL_OFFSET,
  // stateToPeriod は MatchPeriod 写像が同一（前後半・延長・PK）なのでサッカーと共有。
  stateToPeriod(state): MatchPeriod | null {
    return SOCCER_TIMER_CONFIG.stateToPeriod(state)
  },
}

/** フットサル用タイマー（前後半 20 分・延長・PK）。API はサッカーと同形。 */
export function useMatchTimerFutsal(options: UseMatchTimerCoreOptions = {}) {
  const core = useMatchTimerCore(FUTSAL_TIMER_CONFIG, options)

  async function goExtra(): Promise<void> {
    await core.goTo('EXTRA_FIRST', 'SECOND_HALF')
  }
  async function goPenaltyShootout(): Promise<void> {
    await core.goTo('PENALTY_SHOOTOUT', ['EXTRA_SECOND', 'SECOND_HALF'])
  }

  return { ...core, goExtra, goPenaltyShootout }
}

/** フットサル設定で「次状態が COMPLETED か」（サッカーと同じ前後半系）。 */
export function isNextCompletedFutsal(state: TimerState): boolean {
  return (
    (state === 'EXTRA_SECOND' || state === 'PENALTY_SHOOTOUT') &&
    nextIsCompleted(FUTSAL_TIMER_CONFIG, state)
  )
}
