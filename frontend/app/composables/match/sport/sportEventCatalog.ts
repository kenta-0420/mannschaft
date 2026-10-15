/**
 * F08.10 競技別イベント入力カタログ（04_frontend_and_ux.md §G.16 カタログ駆動・
 * sports/0N §8.1 のプリセット並びが正準）。
 *
 * イベント入力シートのプリセット大ボタン（「プリセット＋その他」骨格）を競技別に定義する。
 * シート本体はこのカタログを読んでボタンを描画する（カタログ駆動）。各競技固有のプリセット並び:
 *   - サッカー / フットサル: [得点] [アシスト] [警告/退場] [交代] ＋ [その他]（§8.1）
 *   - バスケ:               [2P] [3P] [FT] [リバウンド] [ファウル] [交代] ＋ [その他]（§8.1）
 *   - バレーボール:         プリセット空（主動線はセット入力シート MatchEventSheetVolleyball）（6-③b §G.16a）
 *
 * i18n ラベルキーのみを持ち（直書き禁止・$t で引く）、実 POST は recorder へ委譲する。
 */
import type { CatalogEventType } from '~/types/match'
import type { AllSport } from '~/composables/match/sport/sportModuleRegistry'

/** プリセットボタンが起動するフロー種別（シート内のステップ機械の分岐キー）。 */
export type SportFlowKind =
  | 'goal' // サッカー得点（GOAL/PENALTY_GOAL/OWN_GOAL 選択＋アシスト連鎖）
  | 'assist' // アシスト起点（→得点連鎖）
  | 'card' // 警告/退場（黄/赤＋理由コード）
  | 'substitution' // 交代（OUT→IN）
  | 'other' // その他（自由ラベル＋note）
  | 'basket_score' // バスケ得点（2P/3P/FT・別フロー）
  | 'rebound' // バスケ リバウンド（選手のみ）
  | 'foul' // バスケ ファウル（PF/TF＋理由コード・5 個目で FOUL_OUT 促し）

/** カタログの 1 プリセットボタン定義。 */
export interface SportPreset {
  /** ボタンが起動するフロー種別。 */
  readonly flow: SportFlowKind
  /** i18n ラベルキー（match.live.preset.* / match.* 配下）。 */
  readonly labelKey: string
  /** PrimeVue アイコン（pi pi-*）。 */
  readonly icon: string
  /** PrimeVue severity（未指定=primary）。 */
  readonly severity?: 'secondary' | 'info' | 'warn' | 'success' | 'danger'
  /**
   * バスケ得点フローで使う既定 event_type（2P/3P/FT の区別）。
   * 得点系プリセットのみ指定する。
   */
  readonly scoreEventType?: CatalogEventType
}

/** 競技別カタログ（プリセット並び＋メタ）。 */
export interface SportCatalog {
  readonly sport: AllSport
  /** 状態モデル類型（連続時間制 / セット制 / ターン制 / 採点制）。 */
  readonly stateModel: 'CONTINUOUS_TIME' | 'SET_BASED' | 'TURN_BASED' | 'SCORED'
  /** イベント入力シートのプリセット並び（画面下部固定）。バレー・将棋・囲碁・採点制は専用シートを使うため空。 */
  readonly presets: readonly SportPreset[]
  /** ポジション語彙（選手グリッド配置・doughnut 集計の大分類）。ターン制・採点制は空（§7・07_scored.md §3）。 */
  readonly positions: readonly string[]
}

/** サッカーのプリセット（§8.1）。 */
const SOCCER_PRESETS: readonly SportPreset[] = [
  { flow: 'goal', labelKey: 'match.live.preset.goal', icon: 'pi pi-flag' },
  { flow: 'assist', labelKey: 'match.live.preset.assist', icon: 'pi pi-share-alt', severity: 'info' },
  { flow: 'card', labelKey: 'match.live.preset.card', icon: 'pi pi-stop', severity: 'warn' },
  { flow: 'substitution', labelKey: 'match.live.preset.substitution', icon: 'pi pi-sync', severity: 'secondary' },
  { flow: 'other', labelKey: 'match.live.preset.other', icon: 'pi pi-ellipsis-h', severity: 'secondary' },
]

/** バスケのプリセット（§8.1: 2P/3P/FT/リバウンド/ファウル/交代＋その他）。 */
const BASKETBALL_PRESETS: readonly SportPreset[] = [
  { flow: 'basket_score', labelKey: 'match.live.preset.field_goal_2', icon: 'pi pi-circle', scoreEventType: 'FIELD_GOAL_2' },
  { flow: 'basket_score', labelKey: 'match.live.preset.field_goal_3', icon: 'pi pi-circle-fill', scoreEventType: 'FIELD_GOAL_3' },
  { flow: 'basket_score', labelKey: 'match.live.preset.free_throw', icon: 'pi pi-minus-circle', severity: 'info', scoreEventType: 'FREE_THROW' },
  { flow: 'rebound', labelKey: 'match.live.preset.rebound', icon: 'pi pi-refresh', severity: 'secondary' },
  { flow: 'foul', labelKey: 'match.live.preset.foul', icon: 'pi pi-exclamation-triangle', severity: 'warn' },
  { flow: 'substitution', labelKey: 'match.live.preset.substitution', icon: 'pi pi-sync', severity: 'secondary' },
  { flow: 'other', labelKey: 'match.live.preset.other', icon: 'pi pi-ellipsis-h', severity: 'secondary' },
]

/**
 * バレーボールのプリセット（空）。
 * 主動線はセット入力シート（MatchEventSheetVolleyball）が担うため、
 * プリセット大ボタンは使用しない（§G.16a）。
 */
const VOLLEYBALL_PRESETS: readonly SportPreset[] = []

/**
 * 将棋/囲碁のプリセット（空）。
 * 主動線はターン制結果入力シート（MatchEventSheetTurnBased）が担うため、
 * プリセット大ボタンは使用しない（§G.16a・ターン制 UI はタイムラインでなく結果入力）。
 */
const TURN_PRESETS: readonly SportPreset[] = []

/** 競技別カタログ定義（連続時間制 3 競技 + セット制 1 競技 + ターン制 2 競技 + 採点制 2 競技）。 */
export const SPORT_CATALOGS: Readonly<Record<AllSport, SportCatalog>> = {
  SOCCER: { sport: 'SOCCER', stateModel: 'CONTINUOUS_TIME', presets: SOCCER_PRESETS, positions: ['GK', 'DF', 'MF', 'FW'] },
  FUTSAL: { sport: 'FUTSAL', stateModel: 'CONTINUOUS_TIME', presets: SOCCER_PRESETS, positions: ['GK', 'FIXO', 'ALA', 'PIVO'] },
  BASKETBALL: { sport: 'BASKETBALL', stateModel: 'CONTINUOUS_TIME', presets: BASKETBALL_PRESETS, positions: ['PG', 'SG', 'SF', 'PF', 'C'] },
  VOLLEYBALL: { sport: 'VOLLEYBALL', stateModel: 'SET_BASED', presets: VOLLEYBALL_PRESETS, positions: ['OH', 'OP', 'MB', 'S', 'L'] },
  // ターン制（将棋・囲碁）: ポジション語彙なし（§7）・プリセット空（専用シートを使う）
  SHOGI: { sport: 'SHOGI', stateModel: 'TURN_BASED', presets: TURN_PRESETS, positions: [] },
  GO: { sport: 'GO', stateModel: 'TURN_BASED', presets: TURN_PRESETS, positions: [] },
  // 採点制（フィギュアスケート・体操）: ポジション語彙なし・プリセット空（採点入力シートを使う）
  // MVP は合計点のみ（整数スケール×1000）。出場交代概念なし（07_scored.md §3 / §9）
  FIGURE_SKATING: { sport: 'FIGURE_SKATING', stateModel: 'SCORED', presets: [], positions: [] },
  GYMNASTICS: { sport: 'GYMNASTICS', stateModel: 'SCORED', presets: [], positions: [] },
}

/** サッカー/フットサルの共通シート（MatchEventSheet）を使う競技か。 */
export function usesSoccerStyleSheet(sport: AllSport | null | undefined): boolean {
  return sport === 'SOCCER' || sport === 'FUTSAL'
}

/** 競技のカタログを取得（未登録競技は null）。 */
export function getSportCatalog(sport: AllSport | null | undefined): SportCatalog | null {
  if (!sport) return null
  return SPORT_CATALOGS[sport] ?? null
}