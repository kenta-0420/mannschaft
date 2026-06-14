/**
 * F08.10 競技別ライブモジュールの動的 import レジストリ（04_frontend_and_ux.md §G.16）。
 *
 * 共通シェル（live.vue）は `matches.sport` に応じて**競技モジュールを `import()` で遅延読込**する。
 * これにより、サッカーしか使わないユーザーはバスケのタイマー・入力シート・カタログを
 * 初期バンドルに同梱しない（Nuxt/Vite のコード分割に乗る＝マスター懸念「重くなる」の解消）。
 *
 * 各競技モジュールは「タイマー composable のファクトリ＋入力シートの遅延コンポーネント＋
 * 状態モデル類型＋次状態 COMPLETED 判定」を返す。共通シェルはこの統一 IF だけを知る
 * （新競技追加時はレジストリに 1 エントリ足すだけで済む＝§G.16 保守性）。
 *
 * 【重要】レジストリの値は **`() => import(...)` の関数**であること（即時 import しない）。
 * 即時 import するとビルド時に全競技が初期チャンクへ巻き込まれ lazy-load にならない。
 *
 * 【状態モデル類型（CONTINUOUS_TIME / SET_BASED / TURN_BASED）】
 * SportLiveModule は 3 類型のディスクリミネーテッドユニオン（stateModel で識別）。live.vue は
 * stateModel で分岐する。VOLLEYBALL=SET_BASED・SHOGI/GO=TURN_BASED・他=CONTINUOUS_TIME。
 *
 * 【生成型への返済（旧前向きユニオン）】
 * 旧来は BE OpenAPI の `Sport` が 'SOCCER' のみで、FE が VOLLEYBALL/SHOGI/GO を手書きの前向きユニオン
 * （AllSport）で先行定義していた。OpenAPI 再生成で生成型 `Sport` が全 6 競技へ拡張されたため、
 * `AllSport` を生成型 `Sport`（`~/types/match`）へ返済した（手書き列挙を撤去・生成型が正本）。
 */
import type { Component } from 'vue'
import type { LiveSport, MatchPeriod, Sport } from '~/types/match'
import type {
  TimerState,
  UseMatchTimerCoreOptions,
} from '~/composables/match/sport/useMatchTimerCore'
import type { MatchSetTrackerReturn } from '~/composables/match/sport/useMatchSetTracker'
import type { MatchTurnTrackerReturn } from '~/composables/match/sport/useMatchTurnTracker'

/**
 * 競技別タイマー composable の共通シェイプ（核 useMatchTimerCore の返り値の構造的上位型）。
 * 各競技 composable（useMatchTimerSoccer/Futsal/Basketball）の返り値はこの型に構造的に代入できる
 * （核の返り値 ＋ 競技別の任意分岐メソッド）。型の偽装（as cast）を避けるため、フィールドの型は
 * 核の実体（ComputedRef も Ref として読める）に合わせている。
 * 競技別の追加メソッド（goExtra / goPenaltyShootout / goOvertime）は任意。
 */
export interface SportTimer {
  state: Ref<TimerState>
  currentMinute: Readonly<Ref<number | null>>
  displayClock: Readonly<Ref<string>>
  isRunning: Readonly<Ref<boolean>>
  lastActivePeriod: Ref<MatchPeriod | null>
  stoppageMinute: Ref<number | null>
  /** 状態 → MatchPeriod 写像（競技差分・記録側の period 丸めに使う）。 */
  stateToPeriod: (state: TimerState) => MatchPeriod | null
  advance: () => Promise<void>
  complete: () => Promise<void>
  overrideMinute: (minute: number | null) => void
  restore: (r: { state?: TimerState; elapsedSeconds?: number }) => void
  // 前後半系（サッカー/フットサル）
  goExtra?: () => Promise<void>
  goPenaltyShootout?: () => Promise<void>
  // クォーター系（バスケ）
  goOvertime?: () => Promise<void>
}

/**
 * 連続時間制（CONTINUOUS_TIME）競技モジュール（サッカー/フットサル/バスケ）。
 * タイマー composable を提供する。
 */
export interface SportLiveModuleContinuous {
  readonly sport: LiveSport
  readonly stateModel: 'CONTINUOUS_TIME'
  /** タイマー composable のファクトリ（onPeriodTransition フックを受ける）。 */
  createTimer(options?: UseMatchTimerCoreOptions): SportTimer
  /** イベント入力シートの遅延コンポーネント（defineAsyncComponent 済み）。 */
  readonly eventSheet: Component
  /** 次状態が COMPLETED になる遷移か（live.vue の handleAdvance 判定）。 */
  isNextCompleted(state: TimerState): boolean
}

/**
 * セット制（SET_BASED）競技モジュール（バレーボール）。
 * タイマーを持たず、セットトラッカーを提供する。
 * セット入力シートが直接セット進行・デュース判定・試合終了を制御する（§8.5）。
 */
export interface SportLiveModuleSetBased {
  readonly sport: AllSport
  readonly stateModel: 'SET_BASED'
  /** セットトラッカー composable のファクトリ。 */
  createSetTracker(): MatchSetTrackerReturn
  /** セット入力シートの遅延コンポーネント（defineAsyncComponent 済み）。 */
  readonly eventSheet: Component
}

/**
 * ターン制（TURN_BASED）競技モジュール（将棋/囲碁）。
 * タイマー・セットトラッカーを持たず、ターン制トラッカーを提供する。
 * 最小結果入力 UI（勝者選択・勝ち方・手数任意・局面写真）を eventSheet として持つ。
 *
 * 【前向きユニオン境界（6-④b）】
 * BE が Sport enum を SHOGI/GO へ拡張し openapi 再生成後、AllSport に統合する。
 */
export interface SportLiveModuleTurnBased {
  readonly sport: AllSport
  readonly stateModel: 'TURN_BASED'
  /** ターン制トラッカー composable のファクトリ。 */
  createTurnTracker(): MatchTurnTrackerReturn
  /** 結果入力シートの遅延コンポーネント（defineAsyncComponent 済み）。 */
  readonly eventSheet: Component
}

/**
 * 競技モジュールのディスクリミネーテッドユニオン（stateModel で識別）。
 */
export type SportLiveModule =
  | SportLiveModuleContinuous
  | SportLiveModuleSetBased
  | SportLiveModuleTurnBased

/**
 * FE が扱う全競技（連続時間制 + セット制 + ターン制）。
 *
 * OpenAPI 再生成で生成型 `Sport`（`~/types/match`）が全 6 競技（SOCCER/FUTSAL/BASKETBALL/
 * VOLLEYBALL/SHOGI/GO）へ拡張されたため、旧・手書き前向きユニオンを撤去し生成型 `Sport` へ返済した。
 * `LiveSport`（連続時間制）はその部分集合（`Exclude` で導出）。
 */
export type AllSport = Sport

/** モジュールローダの型（必ず動的 import を返す関数）。 */
export type SportModuleLoader = () => Promise<SportLiveModule>

/**
 * 競技 → モジュールローダのレジストリ（連続時間制 3 競技 + セット制 1 競技 + ターン制 2 競技）。
 * 値は `() => import(...)` 関数なので、参照するまで対象競技のチャンクは読み込まれない。
 */
const REGISTRY: Readonly<Record<AllSport, SportModuleLoader>> = {
  SOCCER: () => import('~/composables/match/sport/modules/soccerModule').then((m) => m.default),
  FUTSAL: () => import('~/composables/match/sport/modules/futsalModule').then((m) => m.default),
  BASKETBALL: () => import('~/composables/match/sport/modules/basketballModule').then((m) => m.default),
  VOLLEYBALL: () => import('~/composables/match/sport/modules/volleyballModule').then((m) => m.default),
  SHOGI: () => import('~/composables/match/sport/modules/shogiModule').then((m) => m.default),
  GO: () => import('~/composables/match/sport/modules/goModule').then((m) => m.default),
}

/**
 * 競技がレジストリに存在するか（FE 対応済みの全競技か）。
 */
export function isSupportedSport(sport: string | null | undefined): sport is AllSport {
  return !!sport && sport in REGISTRY
}

/**
 * 競技モジュールを動的 import で解決する（lazy-load）。
 *
 * サッカー未指定（sport=null）・未対応競技の既存試合は SOCCER モジュールへフォールバックする
 * （旧データ＝サッカー前提・既存挙動の互換。BE が Sport を拡張するまでの暫定）。
 */
export async function resolveSportModule(
  sport: string | null | undefined,
): Promise<SportLiveModule | null> {
  const key: AllSport = isSupportedSport(sport) ? sport : 'SOCCER'
  const loader = REGISTRY[key]
  if (!loader) return null
  return await loader()
}

/** 型ガード: 連続時間制モジュールか（タイマーを持つ）。 */
export function isContinuousModule(mod: SportLiveModule): mod is SportLiveModuleContinuous {
  return mod.stateModel === 'CONTINUOUS_TIME'
}

/** 型ガード: セット制モジュールか（セットトラッカーを持つ）。 */
export function isSetBasedModule(mod: SportLiveModule): mod is SportLiveModuleSetBased {
  return mod.stateModel === 'SET_BASED'
}

/** 型ガード: ターン制モジュールか（ターントラッカーを持つ）。 */
export function isTurnBasedModule(mod: SportLiveModule): mod is SportLiveModuleTurnBased {
  return mod.stateModel === 'TURN_BASED'
}

export type { TimerState, PeriodTransition } from '~/composables/match/sport/useMatchTimerCore'
