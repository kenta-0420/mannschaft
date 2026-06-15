/**
 * F08.10 採点競技モジュール（動的 import の終端・04 §G.16 / sports/07_scored.md §9）。
 *
 * フィギュアスケート（FIGURE_SKATING）と体操（GYMNASTICS）は同一モジュールを共有する
 * （合計点に還元されてMVPでは競技差が消える・07_scored.md §2.1 / §9）。
 *
 * 採点制（SCORED）はタイマー・セットトラッカー・ターントラッカーを持たず、
 * 採点入力UIのみを提供する（07_scored.md §3）。
 * MVP は合計点のみ（整数スケール×1000・home_score/away_score）を格納する（§4.1）。
 *
 * 【MVP スコープ】合計点入力（2者対戦）。
 * 【後段 Phase】審判別内訳子表（match_scored_components・§4B）、
 *   多人数順位制（match_score_entries・§5B）は後段 Phase で対応。
 *
 * 【eventSheet 暫定】MVP では MatchEventSheetTurnBased を転用し結果入力主動線とする。
 *   採点専用 UI（useMatchScoreEntry composable＋MatchEventSheetScored.vue）は後段 Phase で実装予定。
 */
import { defineAsyncComponent } from 'vue'
import type { SportLiveModuleScored } from '~/composables/match/sport/sportModuleRegistry'

const scoredModule: SportLiveModuleScored = {
  // sport は FIGURE_SKATING / GYMNASTICS 両方で同一インスタンスを共有するため
  // 代表値として FIGURE_SKATING を設定（レジストリの動的 import 終端として機能する）
  sport: 'FIGURE_SKATING',
  stateModel: 'SCORED',
  // TODO(後段 Phase): MatchEventSheetScored.vue + useMatchScoreEntry に差し替える
  eventSheet: defineAsyncComponent(
    () => import('~/components/match/MatchEventSheetTurnBased.vue'),
  ),
}

export default scoredModule
