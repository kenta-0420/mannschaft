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
 * 【前向きユニオン境界（6-③b 追加）】
 * VOLLEYBALL（SET_BASED）を追加。SportLiveModule はディスクリミネーテッドユニオン
 * （CONTINUOUS_TIME / SET_BASED）に拡張。live.vue は stateModel で分岐する。
 * BE が Sport enum を VOLLEYBALL へ拡張し openapi 再生成後、LiveSport ユニオンへ統合する
 * （現時点の border は isSupportedSport / resolveSportModule の 1 箇所・any 禁止）。
 */
import type { Component } from 'vue'
import type { LiveSport, MatchPeriod } from '~/types/match'
import type {
  TimerState,
  UseMatchTimerCoreOptions,
} from '~/composables/match/sport/useMatchTimerCore'
import type { MatchSetTrackerReturn } from '~/composables/match/sport/useMatchSetTracker'

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
 * 競技モジュールのディスクリミネーテッドユニオン（stateModel で識別）。
 *
 * 【前向きユニオン境界】
 * 生成型の `Sport`（BE OpenAPI）は現時点 'SOCCER' のみを含む。
 * FE 先行実装（VOLLEYBALL）は `AllSport` ユニオンを用いる。
 * BE が拡張次第 `LiveSport` と統合する。
 */
export type SportLiveModule = SportLiveModuleContinuous | SportLiveModuleSetBased

/**
 * FE が扱う全競技（連続時間制 + セット制）の前向きユニオン。
 * BE openapi 再生成後に LiveSport へ統合する。
 * TODO: 生成型 Sport に VOLLEYBALL が追加されたら LiveSport に組み込む。
 */
export type AllSport = LiveSport | 'VOLLEYBALL'

/** モジュールローダの型（必ず動的 import を返す関数）。 */
export type SportModuleLoader = () => Promise<SportLiveModule>

/**
 * 競技 → モジュールローダのレジストリ（連続時間制 3 競技 + セット制 1 競技）。
 * 値は `() => import(...)` 関数なので、参照するまで対象競技のチャンクは読み込まれない。
 */
const REGISTRY: Readonly<Record<AllSport, SportModuleLoader>> = {
  SOCCER: () => import('~/composables/match/sport/modules/soccerModule').then((m) => m.default),
  FUTSAL: () => import('~/composables/match/sport/modules/futsalModule').then((m) => m.default),
  BASKETBALL: () => import('~/composables/match/sport/modules/basketballModule').then((m) => m.default),
  VOLLEYBALL: () => import('~/composables/match/sport/modules/volleyballModule').then((m) => m.default),
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

export type { TimerState, PeriodTransition } from '~/composables/match/sport/useMatchTimerCore'
