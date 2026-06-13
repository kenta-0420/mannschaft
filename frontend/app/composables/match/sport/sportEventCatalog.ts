/**
 * F08.10 競技別イベント入力カタログ（04_frontend_and_ux.md §G.16 カタログ駆動・
 * sports/0N §8.1 のプリセット並びが正準）。
 *
 * イベント入力シートのプリセット大ボタン（「プリセット＋その他」骨格）を競技別に定義する。
 * シート本体はこのカタログを読んでボタンを描画する（カタログ駆動）。各競技固有のプリセット並び:
 *   - サッカー / フットサル: [得点] [アシスト] [警告/退場] [交代] ＋ [その他]（§8.1）
 *   - バスケ:               [2P] [3P] [FT] [リバウンド] [ファウル] [交代] ＋ [その他]（§8.1）
 *
 * i18n ラベルキーのみを持ち（直書き禁止・$t で引く）、実 POST は recorder へ委譲する。
 */
import type { CatalogEventType, LiveSport } from '~/types/match'

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
  readonly sport: LiveSport
  /** 状態モデル類型（本波は連続時間制のみ）。 */
  readonly stateModel: 'CONTINUOUS_TIME'
  /** イベント入力シートのプリセット並び（画面下部固定）。 */
  readonly presets: readonly SportPreset[]
  /** ポジション語彙（選手グリッド配置・doughnut 集計の大分類）。 */
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

/** 競技別カタログ定義（本波＝連続時間制 3 競技）。 */
export const SPORT_CATALOGS: Readonly<Record<LiveSport, SportCatalog>> = {
  SOCCER: { sport: 'SOCCER', stateModel: 'CONTINUOUS_TIME', presets: SOCCER_PRESETS, positions: ['GK', 'DF', 'MF', 'FW'] },
  FUTSAL: { sport: 'FUTSAL', stateModel: 'CONTINUOUS_TIME', presets: SOCCER_PRESETS, positions: ['GK', 'FIXO', 'ALA', 'PIVO'] },
  BASKETBALL: { sport: 'BASKETBALL', stateModel: 'CONTINUOUS_TIME', presets: BASKETBALL_PRESETS, positions: ['PG', 'SG', 'SF', 'PF', 'C'] },
}

/** サッカー/フットサルの共通シート（MatchEventSheet）を使う競技か。 */
export function usesSoccerStyleSheet(sport: LiveSport | null | undefined): boolean {
  return sport === 'SOCCER' || sport === 'FUTSAL'
}

/** 競技のカタログを取得（未登録競技は null）。 */
export function getSportCatalog(sport: LiveSport | null | undefined): SportCatalog | null {
  if (!sport) return null
  return SPORT_CATALOGS[sport] ?? null
}
