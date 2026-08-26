/**
 * F08.10 タイマー状態機械（04_frontend_and_ux.md §G.2 / §G.16 / sports/01_soccer.md §8.5）。
 *
 * 【6-②b 多競技化】本ファイルはサッカー実装の**後方互換ファサード**となった。
 * 競技別タイマーは `composables/match/sport/` 配下に分割した:
 *   - useMatchTimerCore       … 連続時間制の共通核（状態機械・minute 補完・elapsed）
 *   - useMatchTimerSoccer     … サッカー（前後半・延長・PK・本ファイルが委譲する先）
 *   - useMatchTimerFutsal     … フットサル（前後半 20 分）
 *   - useMatchTimerBasketball … バスケ（4 クォーター＋OT）
 *
 * 既存の import（useMatchTimer / isNextCompleted / stateToPeriod / TimerState / PeriodTransition）は
 * すべてここから再エクスポートして維持する（挙動不変・移行は段階的）。
 *
 * サッカーのピリオド遷移:
 *   WAITING → FIRST_HALF → HALF_TIME → SECOND_HALF
 *           → [EXTRA_FIRST → EXTRA_SECOND] → [PENALTY_SHOOTOUT] → COMPLETED
 */
import {
  SOCCER_TIMER_CONFIG,
  useMatchTimerSoccer,
  isNextCompletedSoccer,
} from '~/composables/match/sport/useMatchTimerSoccer'
import type {
  TimerState,
  UseMatchTimerCoreOptions,
} from '~/composables/match/sport/useMatchTimerCore'
import type { MatchPeriod } from '~/types/match'

export type { TimerState, PeriodTransition } from '~/composables/match/sport/useMatchTimerCore'

/** 旧名のオプション型（互換）。 */
export type UseMatchTimerOptions = UseMatchTimerCoreOptions

/**
 * 指定状態から advance したとき次状態が COMPLETED になるか（サッカー）。
 * live.vue が「advance ではなく completeMatch を呼ぶべき」か判定するために使う。
 * EXTRA_SECOND / PENALTY_SHOOTOUT が該当。
 */
export function isNextCompleted(state: TimerState): boolean {
  return isNextCompletedSoccer(state)
}

/** 状態が「タイマー動作中（経過を刻む）」か（サッカー）。 */
export function isRunningState(state: TimerState): boolean {
  return SOCCER_TIMER_CONFIG.isRunningState(state)
}

/** 動作状態 → 対応する MatchPeriod（停止状態は null・サッカー）。 */
export function stateToPeriod(state: TimerState): MatchPeriod | null {
  return SOCCER_TIMER_CONFIG.stateToPeriod(state)
}

/** サッカー用タイマー（旧 API・useMatchTimerSoccer への委譲）。 */
export function useMatchTimer(options: UseMatchTimerOptions = {}) {
  return useMatchTimerSoccer(options)
}
