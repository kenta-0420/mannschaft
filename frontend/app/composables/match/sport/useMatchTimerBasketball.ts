/**
 * F08.10 バスケットボール用タイマー composable（CONTINUOUS_TIME・4 クォーター＋OT・
 * sports/03_basketball.md §8.5）。
 *
 * 状態遷移（§8.5）:
 *   WAITING → QUARTER_1 → BREAK_1 → QUARTER_2 → HALF_TIME_BREAK
 *           → QUARTER_3 → BREAK_3 → QUARTER_4 → [OVERTIME ...] → COMPLETED
 *   （[] = 任意。同点時のみ OVERTIME・複数回あり得る）
 *
 * サッカーの前後半とは状態遷移が根本的に異なるため別 composable とする（コア 04 §G.16）。
 * - 動作状態: QUARTER_1〜4 / OVERTIME（PERIOD_START 基準で minute 自動補完）
 * - 停止状態: WAITING / BREAK_1 / HALF_TIME_BREAK / BREAK_3 / COMPLETED
 * - period（match_events.period）: QUARTER_1〜4 / OVERTIME（停止状態は直近の進行ピリオドへ丸め）
 * - 複数回 OT は MVP では単一 OVERTIME 値で扱う（§3・得点は period 値に依らず本戦合算）。
 *   OVERTIME からの「もう一度 OT」は goOvertime() で同じ OVERTIME へ再遷移できる
 *   （PERIOD_START/END が都度記録され sort_seq で時系列を保つ・§3）。
 */
import type { MatchPeriod } from '~/types/match'
import {
  useMatchTimerCore,
  nextIsCompleted,
  type TimerState,
  type TimerSportConfig,
  type UseMatchTimerCoreOptions,
} from '~/composables/match/sport/useMatchTimerCore'

/** クォーター（各 10 分・FIBA）の minute 起算オフセット。OT は 40 分起算。 */
const BASKETBALL_OFFSET: Partial<Record<TimerState, number>> = {
  WAITING: 0,
  QUARTER_1: 0,
  BREAK_1: 10,
  QUARTER_2: 10,
  HALF_TIME_BREAK: 20,
  QUARTER_3: 20,
  BREAK_3: 30,
  QUARTER_4: 30,
  OVERTIME: 40,
  COMPLETED: 40,
}

/** 標準 advance 遷移（OT へは goOvertime で明示分岐）。 */
const BASKETBALL_NEXT: Partial<Record<TimerState, TimerState | null>> = {
  WAITING: 'QUARTER_1',
  QUARTER_1: 'BREAK_1',
  BREAK_1: 'QUARTER_2',
  QUARTER_2: 'HALF_TIME_BREAK',
  HALF_TIME_BREAK: 'QUARTER_3',
  QUARTER_3: 'BREAK_3',
  BREAK_3: 'QUARTER_4',
  QUARTER_4: 'COMPLETED', // 同点時のみ OVERTIME（goOvertime で分岐）
  OVERTIME: 'COMPLETED',
  COMPLETED: null,
}

/** バスケ（4 クォーター＋OT）のタイマー設定。 */
export const BASKETBALL_TIMER_CONFIG: TimerSportConfig = {
  sport: 'BASKETBALL',
  initialState: 'WAITING',
  nextState: BASKETBALL_NEXT,
  periodMinuteOffset: BASKETBALL_OFFSET,
  isRunningState(state) {
    return (
      state === 'QUARTER_1' ||
      state === 'QUARTER_2' ||
      state === 'QUARTER_3' ||
      state === 'QUARTER_4' ||
      state === 'OVERTIME'
    )
  },
  stateToPeriod(state): MatchPeriod | null {
    switch (state) {
      case 'QUARTER_1':
        return 'QUARTER_1'
      case 'QUARTER_2':
        return 'QUARTER_2'
      case 'QUARTER_3':
        return 'QUARTER_3'
      case 'QUARTER_4':
        return 'QUARTER_4'
      case 'OVERTIME':
        return 'OVERTIME'
      default:
        return null // WAITING / BREAK_* / HALF_TIME_BREAK / COMPLETED
    }
  },
  isMinuteless(state) {
    // 停止状態は minute を出さない（直近進行ピリオドへ寄せるのは記録側の責務）。
    return (
      state === 'WAITING' ||
      state === 'BREAK_1' ||
      state === 'HALF_TIME_BREAK' ||
      state === 'BREAK_3' ||
      state === 'COMPLETED'
    )
  },
}

/** バスケ用タイマー（4 クォーター＋OT）。同点時の OT 投入分岐を備える。 */
export function useMatchTimerBasketball(options: UseMatchTimerCoreOptions = {}) {
  const core = useMatchTimerCore(BASKETBALL_TIMER_CONFIG, options)

  /**
   * オーバータイムへ進む（QUARTER_4 終了で同点 / OVERTIME 終了でなお同点のとき）。
   * QUARTER_4 または OVERTIME からのみ。OVERTIME→OVERTIME は複数回 OT の再投入。
   */
  async function goOvertime(): Promise<void> {
    await core.goTo('OVERTIME', ['QUARTER_4', 'OVERTIME'])
  }

  return { ...core, goOvertime }
}

/** バスケ設定で「次状態が COMPLETED か」（QUARTER_4 / OVERTIME が該当）。 */
export function isNextCompletedBasketball(state: TimerState): boolean {
  return (
    (state === 'QUARTER_4' || state === 'OVERTIME') &&
    nextIsCompleted(BASKETBALL_TIMER_CONFIG, state)
  )
}
