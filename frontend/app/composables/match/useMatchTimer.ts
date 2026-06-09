/**
 * F08.10 タイマー状態機械（04_frontend_and_ux.md §G.2 / sports/01_soccer.md §8.5）。
 *
 * サッカーのピリオド遷移:
 *   WAITING → FIRST_HALF → HALF_TIME → SECOND_HALF
 *           → [EXTRA_FIRST → EXTRA_SECOND] → [PENALTY_SHOOTOUT] → COMPLETED
 *
 * - 各「動作」状態（FIRST_HALF/SECOND_HALF/EXTRA_*）では setInterval で経過秒を刻む。
 *   「停止」状態（WAITING/HALF_TIME/PENALTY_SHOOTOUT/COMPLETED）ではタイマーを止める。
 * - minute は PERIOD_START 基準＋ピリオドオフセット（前半 0 分・後半 45 分起算…）で自動補完する。
 *   ただし **手動訂正可**（`overrideMinute()`）。
 * - ピリオド切替（advance）で PERIOD_END（前ピリオド）/ PERIOD_START（新ピリオド）の
 *   イベントを自動記録するためのフックを `onPeriodTransition` で受け取る。
 *
 * 本 composable は「状態と経過時間」のみを司り、イベント POST は呼び出し側（live.vue）が
 * onPeriodTransition コールバックで useMatchLiveRecorder 経由に委譲する（関心の分離）。
 */
import type { MatchPeriod } from '~/types/match'

/** UI タイマー状態（ピリオド＋停止状態を統合した状態機械の状態）。 */
export type TimerState =
  | 'WAITING'
  | 'FIRST_HALF'
  | 'HALF_TIME'
  | 'SECOND_HALF'
  | 'EXTRA_FIRST'
  | 'EXTRA_SECOND'
  | 'PENALTY_SHOOTOUT'
  | 'COMPLETED'

/** ピリオド切替時に呼ばれるフックの引数。 */
export interface PeriodTransition {
  /** 終了するピリオド（PERIOD_END 対象・WAITING からの開始時は null） */
  endingPeriod: MatchPeriod | null
  /** 開始するピリオド（PERIOD_START 対象・COMPLETED への遷移時は null） */
  startingPeriod: MatchPeriod | null
  /** 切替が起きた時点の自動補完 minute */
  minute: number | null
}

/** 状態が「タイマー動作中（経過を刻む）」か。 */
export function isRunningState(state: TimerState): boolean {
  return (
    state === 'FIRST_HALF' ||
    state === 'SECOND_HALF' ||
    state === 'EXTRA_FIRST' ||
    state === 'EXTRA_SECOND'
  )
}

/** 動作状態 → 対応する MatchPeriod（停止状態は null）。 */
export function stateToPeriod(state: TimerState): MatchPeriod | null {
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
      // WAITING / HALF_TIME / COMPLETED は MatchPeriod を持たない
      return null
  }
}

/**
 * 各ピリオドの「経過分の起算オフセット（分）」。
 * minute の自動補完は offset + 当該ピリオド開始からの経過分。
 * サッカー: 前半 0・後半 45・延長前半 90・延長後半 105 起算（標準的な前後半 45 分想定）。
 */
const PERIOD_MINUTE_OFFSET: Record<TimerState, number> = {
  WAITING: 0,
  FIRST_HALF: 0,
  HALF_TIME: 45,
  SECOND_HALF: 45,
  EXTRA_FIRST: 90,
  EXTRA_SECOND: 105,
  PENALTY_SHOOTOUT: 120,
  COMPLETED: 120,
}

/** 各状態から「次に進む」状態（advance の標準遷移）。延長/PK はスキップ用に skipTo で飛べる。 */
const NEXT_STATE: Record<TimerState, TimerState | null> = {
  WAITING: 'FIRST_HALF',
  FIRST_HALF: 'HALF_TIME',
  HALF_TIME: 'SECOND_HALF',
  SECOND_HALF: 'COMPLETED', // 延長なしが標準（延長へは goExtra で明示分岐）
  EXTRA_FIRST: 'EXTRA_SECOND',
  EXTRA_SECOND: 'COMPLETED', // PK へは goPenaltyShootout で明示分岐
  PENALTY_SHOOTOUT: 'COMPLETED',
  COMPLETED: null,
}

export interface UseMatchTimerOptions {
  /** ピリオド切替時に PERIOD_START/PERIOD_END を記録するためのフック。 */
  onPeriodTransition?: (t: PeriodTransition) => void | Promise<void>
}

export function useMatchTimer(options: UseMatchTimerOptions = {}) {
  const state = ref<TimerState>('WAITING')
  /** 現ピリオド開始からの経過秒（動作状態でのみ進む） */
  const elapsedSeconds = ref(0)
  /** 手動訂正された minute（null=自動補完を使う） */
  const manualMinute = ref<number | null>(null)
  /** アディショナルタイム（+N 分）。表示・記録の stoppage に使う */
  const stoppageMinute = ref<number | null>(null)

  let intervalId: ReturnType<typeof setInterval> | null = null

  function clearTick(): void {
    if (intervalId !== null) {
      clearInterval(intervalId)
      intervalId = null
    }
  }

  function startTick(): void {
    clearTick()
    intervalId = setInterval(() => {
      elapsedSeconds.value += 1
    }, 1000)
  }

  /** 状態に応じてタイマーの動作/停止を同期する。 */
  function syncTick(): void {
    if (isRunningState(state.value)) startTick()
    else clearTick()
  }

  /** 現ピリオド開始からの経過分（切り捨て）。 */
  const elapsedMinutesInPeriod = computed(() => Math.floor(elapsedSeconds.value / 60))

  /**
   * 自動補完 minute（手動訂正があればそれを優先）。
   * 停止状態（WAITING）では null（分概念なし）。
   */
  const autoMinute = computed<number | null>(() => {
    if (state.value === 'WAITING') return null
    if (state.value === 'PENALTY_SHOOTOUT') return null // PK 戦は分概念なし
    return PERIOD_MINUTE_OFFSET[state.value] + elapsedMinutesInPeriod.value
  })

  /** 記録に用いる現在 minute（手動訂正 > 自動補完）。 */
  const currentMinute = computed<number | null>(() =>
    manualMinute.value !== null ? manualMinute.value : autoMinute.value,
  )

  /** "MM:SS" 表示（経過秒ベース・停止状態でも現値を出す）。 */
  const displayClock = computed(() => {
    const total = elapsedSeconds.value
    const m = Math.floor(total / 60)
    const s = total % 60
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  })

  /** タイマーが現在動作中か。 */
  const isRunning = computed(() => isRunningState(state.value))

  /** 内部: 状態遷移の共通処理（PERIOD_END→PERIOD_START のフック発火＋経過秒リセット）。 */
  async function transitionTo(next: TimerState): Promise<void> {
    const endingPeriod = stateToPeriod(state.value)
    const startingPeriod = stateToPeriod(next)
    const atMinute = currentMinute.value

    state.value = next
    // 新ピリオドに入ったら経過秒・手動訂正・アディショナルをリセット
    elapsedSeconds.value = 0
    manualMinute.value = null
    stoppageMinute.value = null
    syncTick()

    if (options.onPeriodTransition && (endingPeriod !== null || startingPeriod !== null)) {
      await options.onPeriodTransition({ endingPeriod, startingPeriod, minute: atMinute })
    }
  }

  /** 標準遷移で次の状態へ進む（NEXT_STATE に従う）。 */
  async function advance(): Promise<void> {
    const next = NEXT_STATE[state.value]
    if (next === null) return
    await transitionTo(next)
  }

  /** 後半終了後に延長へ進む（SECOND_HALF からのみ）。 */
  async function goExtra(): Promise<void> {
    if (state.value !== 'SECOND_HALF') return
    await transitionTo('EXTRA_FIRST')
  }

  /** 延長後半終了後に PK 戦へ進む（EXTRA_SECOND からのみ）。 */
  async function goPenaltyShootout(): Promise<void> {
    if (state.value !== 'EXTRA_SECOND' && state.value !== 'SECOND_HALF') return
    await transitionTo('PENALTY_SHOOTOUT')
  }

  /** 試合終了（任意の状態から COMPLETED へ）。 */
  async function complete(): Promise<void> {
    if (state.value === 'COMPLETED') return
    await transitionTo('COMPLETED')
  }

  /** minute の手動訂正（タイマーずれ・後追い入力に対応）。null で自動補完に戻す。 */
  function overrideMinute(minute: number | null): void {
    manualMinute.value = minute
  }

  /** アディショナルタイム（+N）の手動設定。 */
  function setStoppage(minute: number | null): void {
    stoppageMinute.value = minute
  }

  /** タイマーの手動 開始/一時停止（動作状態でのみ意味を持つ）。 */
  function pause(): void {
    clearTick()
  }
  function resume(): void {
    if (isRunningState(state.value)) startTick()
  }

  /** 既存試合（中断→再開）から状態を復元する。 */
  function restore(restored: {
    state?: TimerState
    elapsedSeconds?: number
  }): void {
    if (restored.state) state.value = restored.state
    if (typeof restored.elapsedSeconds === 'number') elapsedSeconds.value = restored.elapsedSeconds
    manualMinute.value = null
    stoppageMinute.value = null
    syncTick()
  }

  onScopeDispose(() => clearTick())

  return {
    // 状態
    state,
    elapsedSeconds,
    manualMinute,
    stoppageMinute,
    // 算出
    autoMinute,
    currentMinute,
    elapsedMinutesInPeriod,
    displayClock,
    isRunning,
    // 遷移
    advance,
    goExtra,
    goPenaltyShootout,
    complete,
    // 手動制御
    overrideMinute,
    setStoppage,
    pause,
    resume,
    restore,
  }
}
