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
 */
import type { Component } from 'vue'
import type { LiveSport, MatchPeriod } from '~/types/match'
import type {
  TimerState,
  UseMatchTimerCoreOptions,
} from '~/composables/match/sport/useMatchTimerCore'

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

/** 競技モジュールの統一インターフェース（動的 import の解決結果）。 */
export interface SportLiveModule {
  readonly sport: LiveSport
  readonly stateModel: 'CONTINUOUS_TIME'
  /** タイマー composable のファクトリ（onPeriodTransition フックを受ける）。 */
  createTimer(options?: UseMatchTimerCoreOptions): SportTimer
  /** イベント入力シートの遅延コンポーネント（defineAsyncComponent 済み）。 */
  readonly eventSheet: Component
  /** 次状態が COMPLETED になる遷移か（live.vue の handleAdvance 判定）。 */
  isNextCompleted(state: TimerState): boolean
}

/** モジュールローダの型（必ず動的 import を返す関数）。 */
export type SportModuleLoader = () => Promise<SportLiveModule>

/**
 * 競技 → モジュールローダのレジストリ（本波＝連続時間制 3 競技）。
 * 値は `() => import(...)` 関数なので、参照するまで対象競技のチャンクは読み込まれない。
 */
const REGISTRY: Readonly<Record<LiveSport, SportModuleLoader>> = {
  SOCCER: () => import('~/composables/match/sport/modules/soccerModule').then((m) => m.default),
  FUTSAL: () => import('~/composables/match/sport/modules/futsalModule').then((m) => m.default),
  BASKETBALL: () => import('~/composables/match/sport/modules/basketballModule').then((m) => m.default),
}

/**
 * 競技がレジストリに存在するか（FE 対応済みの連続時間制競技か）。
 * セット制（バレー）・ターン制（将棋/囲碁）は後続波で追加される。
 */
export function isSupportedSport(sport: string | null | undefined): sport is LiveSport {
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
  const key: LiveSport = isSupportedSport(sport) ? sport : 'SOCCER'
  const loader = REGISTRY[key]
  if (!loader) return null
  return await loader()
}

export type { TimerState, PeriodTransition } from '~/composables/match/sport/useMatchTimerCore'
