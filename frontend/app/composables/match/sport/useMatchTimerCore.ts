/**
 * F08.10 競技別タイマーの共通核（04_frontend_and_ux.md §G.16 競技別 composable の分割）。
 *
 * 連続時間制（CONTINUOUS_TIME・01 §D.6）の競技（サッカー / フットサル / バスケ）は、
 * いずれも「停止/動作状態を持つ状態機械＋PERIOD_START 基準の minute 自動補完＋手動訂正」
 * という**共通の骨格**を持つ。差分は
 *   - 状態集合（前後半か 4 クォーターか）
 *   - 状態遷移表（NEXT_STATE）
 *   - 各状態の minute 起算オフセット（PERIOD_MINUTE_OFFSET）
 *   - 動作状態の判定 / 状態→MatchPeriod の写像
 *   - 任意分岐（延長・PK 戦 / OT 投入）
 * のみであり、これらを {@link TimerSportConfig} として競技別に注入する。
 *
 * 本核は「状態と経過時間」のみを司り、イベント POST は呼び出し側（live.vue / useMatchLiveSession）が
 * onPeriodTransition コールバックで useMatchLiveRecorder 経由に委譲する（関心の分離・既存設計踏襲）。
 *
 * 既存のサッカー実装（useMatchTimer.ts）はこの核に薄く委譲する形へ移行した（挙動不変）。
 */
import type { MatchPeriod } from '~/types/match'

/**
 * UI タイマー状態（全連続時間制競技の状態を保持する器）。
 * サッカー/フットサルは前後半系、バスケはクォーター系の部分集合を使う。
 * 競技ごとに使う部分集合は {@link TimerSportConfig.initialState} と NEXT_STATE で決まる。
 */
export type TimerState =
  // --- 共通の停止/開始/終了 ---
  | 'WAITING'
  | 'COMPLETED'
  // --- サッカー / フットサル（前後半系・01/02 §8.5） ---
  | 'FIRST_HALF'
  | 'HALF_TIME'
  | 'SECOND_HALF'
  | 'EXTRA_FIRST'
  | 'EXTRA_SECOND'
  | 'PENALTY_SHOOTOUT'
  // --- バスケ（4 クォーター＋OT・03 §8.5） ---
  | 'QUARTER_1'
  | 'BREAK_1'
  | 'QUARTER_2'
  | 'HALF_TIME_BREAK'
  | 'QUARTER_3'
  | 'BREAK_3'
  | 'QUARTER_4'
  | 'OVERTIME'

/** ピリオド切替時に呼ばれるフックの引数（PERIOD_START/PERIOD_END 自動記録用）。 */
export interface PeriodTransition {
  /** 終了するピリオド（PERIOD_END 対象・開始時は null） */
  endingPeriod: MatchPeriod | null
  /** 開始するピリオド（PERIOD_START 対象・COMPLETED への遷移時は null） */
  startingPeriod: MatchPeriod | null
  /** 切替が起きた時点の自動補完 minute */
  minute: number | null
}

/**
 * 競技別タイマー設定。共通核に注入する差分定義。
 * これ 1 つを競技別 composable（useMatchTimerSoccer/Futsal/Basketball）が用意する。
 */
export interface TimerSportConfig {
  /** 競技識別（デバッグ・テスト用）。 */
  readonly sport: string
  /** 初期状態（通常 WAITING）。 */
  readonly initialState: TimerState
  /** 各状態 → 標準 advance 先（終端は null）。 */
  readonly nextState: Readonly<Partial<Record<TimerState, TimerState | null>>>
  /** 各状態の minute 起算オフセット（分）。動作状態でのみ意味を持つ。 */
  readonly periodMinuteOffset: Readonly<Partial<Record<TimerState, number>>>
  /** その状態がタイマー動作中（経過秒を刻む）か。 */
  isRunningState(state: TimerState): boolean
  /** 状態 → 対応する MatchPeriod（停止状態は null）。 */
  stateToPeriod(state: TimerState): MatchPeriod | null
  /** その状態で minute を null にすべきか（PK 戦・WAITING 等の「分概念なし」）。 */
  isMinuteless?(state: TimerState): boolean
}

export interface UseMatchTimerCoreOptions {
  /** ピリオド切替時に PERIOD_START/PERIOD_END を記録するためのフック。 */
  onPeriodTransition?: (t: PeriodTransition) => void | Promise<void>
}

/**
 * 連続時間制タイマーの共通核。{@link TimerSportConfig} で競技差分を注入する。
 * 返り値の API は従来の useMatchTimer と互換（state/currentMinute/advance/complete/restore 等）。
 */
export function useMatchTimerCore(
  config: TimerSportConfig,
  options: UseMatchTimerCoreOptions = {},
) {
  const state = ref<TimerState>(config.initialState)
  /** 現ピリオド開始からの経過秒（動作状態でのみ進む） */
  const elapsedSeconds = ref(0)
  /** 手動訂正された minute（null=自動補完を使う） */
  const manualMinute = ref<number | null>(null)
  /** アディショナルタイム（+N 分） */
  const stoppageMinute = ref<number | null>(null)
  /**
   * 直近で「具体ピリオドだった」状態。停止状態（WAITING/HALF_TIME/BREAK/COMPLETED）には
   * match_events.period の具体値が無いため、停止中に記録されたイベントの period 丸めに用いる。
   */
  const lastActivePeriod = ref<MatchPeriod | null>(null)

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
  function syncTick(): void {
    if (config.isRunningState(state.value)) startTick()
    else clearTick()
  }

  const elapsedMinutesInPeriod = computed(() => Math.floor(elapsedSeconds.value / 60))

  function minuteless(s: TimerState): boolean {
    if (config.isMinuteless) return config.isMinuteless(s)
    return s === 'WAITING'
  }

  /** 自動補完 minute（手動訂正があればそれを優先・分概念なしの状態は null）。 */
  const autoMinute = computed<number | null>(() => {
    if (minuteless(state.value)) return null
    const offset = config.periodMinuteOffset[state.value] ?? 0
    return offset + elapsedMinutesInPeriod.value
  })

  /** 記録に用いる現在 minute（手動訂正 > 自動補完）。 */
  const currentMinute = computed<number | null>(() =>
    manualMinute.value !== null ? manualMinute.value : autoMinute.value,
  )

  /** "MM:SS" 表示（経過秒ベース）。 */
  const displayClock = computed(() => {
    const total = elapsedSeconds.value
    const m = Math.floor(total / 60)
    const s = total % 60
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  })

  const isRunning = computed(() => config.isRunningState(state.value))

  /** 内部: 状態遷移の共通処理（PERIOD_END→PERIOD_START フック発火＋経過秒リセット）。 */
  async function transitionTo(next: TimerState): Promise<void> {
    const endingPeriod = config.stateToPeriod(state.value)
    const startingPeriod = config.stateToPeriod(next)
    const atMinute = currentMinute.value

    if (startingPeriod !== null) lastActivePeriod.value = startingPeriod
    else if (endingPeriod !== null) lastActivePeriod.value = endingPeriod

    state.value = next
    elapsedSeconds.value = 0
    manualMinute.value = null
    stoppageMinute.value = null
    syncTick()

    if (options.onPeriodTransition && (endingPeriod !== null || startingPeriod !== null)) {
      await options.onPeriodTransition({ endingPeriod, startingPeriod, minute: atMinute })
    }
  }

  /** 標準遷移で次の状態へ進む（config.nextState に従う）。 */
  async function advance(): Promise<void> {
    const next = config.nextState[state.value]
    if (next === null || next === undefined) return
    await transitionTo(next)
  }

  /**
   * 任意の状態へ明示遷移する（延長 / PK / OT 投入 等の分岐）。
   * 競技別 composable が goExtra/goPenaltyShootout/goOvertime をこの上に構築する。
   * `from` を指定すると現状態が一致したときのみ遷移する（ガード）。
   */
  async function goTo(target: TimerState, from?: TimerState | TimerState[]): Promise<void> {
    if (from !== undefined) {
      const allowed = Array.isArray(from) ? from : [from]
      if (!allowed.includes(state.value)) return
    }
    await transitionTo(target)
  }

  /** 試合終了（任意の状態から COMPLETED へ）。 */
  async function complete(): Promise<void> {
    if (state.value === 'COMPLETED') return
    await transitionTo('COMPLETED')
  }

  function overrideMinute(minute: number | null): void {
    manualMinute.value = minute
  }
  function setStoppage(minute: number | null): void {
    stoppageMinute.value = minute
  }
  function pause(): void {
    clearTick()
  }
  function resume(): void {
    if (config.isRunningState(state.value)) startTick()
  }

  /** 既存試合（中断→再開）から状態を復元する。 */
  function restore(restored: { state?: TimerState; elapsedSeconds?: number }): void {
    if (restored.state) {
      state.value = restored.state
      const restoredPeriod = config.stateToPeriod(restored.state)
      if (restoredPeriod !== null) lastActivePeriod.value = restoredPeriod
    }
    if (typeof restored.elapsedSeconds === 'number') elapsedSeconds.value = restored.elapsedSeconds
    manualMinute.value = null
    stoppageMinute.value = null
    syncTick()
  }

  onScopeDispose(() => clearTick())

  return {
    // 設定（テスト・デバッグ）
    sport: config.sport,
    /** 状態 → MatchPeriod の写像（競技差分・記録側の period 丸めに使う）。 */
    stateToPeriod: (s: TimerState) => config.stateToPeriod(s),
    // 状態
    state,
    elapsedSeconds,
    manualMinute,
    stoppageMinute,
    lastActivePeriod,
    // 算出
    autoMinute,
    currentMinute,
    elapsedMinutesInPeriod,
    displayClock,
    isRunning,
    // 遷移
    advance,
    goTo,
    complete,
    // 手動制御
    overrideMinute,
    setStoppage,
    pause,
    resume,
    restore,
  }
}

/** 競技横断の共通ヘルパ: config から「次状態が COMPLETED か」を判定する。 */
export function nextIsCompleted(config: TimerSportConfig, state: TimerState): boolean {
  return config.nextState[state] === 'COMPLETED'
}
