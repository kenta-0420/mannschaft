/**
 * F08.10 採点競技モジュール（動的 import の終端・04 §G.16 / sports/07_scored.md §9）。
 *
 * フィギュアスケート（FIGURE_SKATING）と体操（GYMNASTICS）は同一モジュールを共有する
 * （合計点に還元されてMVPでは競技差が消える・07_scored.md §2.1 / §9）。
 *
 * 採点制（SCORED）はタイマー・セットトラッカー・ターントラッカーを持たず、
 * 採点入力UI（合計点・2 者対戦）のみを提供する（07_scored.md §3）。
 * MVP は合計点のみ（整数スケール×1000・home_score/away_score）を格納する（§4.1）。
 *
 * 【MVP スコープ】合計点入力（2者対戦）。useMatchScoreEntry で状態管理＋
 *   PUT /scored-result の API 呼び出しを行い、MatchEventSheetScored.vue が入力 UI を担う。
 * 【後段 Phase】審判別内訳子表（match_scored_components・§4B）、
 *   多人数順位制（match_score_entries・§5B）は後段 Phase で対応（BE 未実装）。
 */
import { defineAsyncComponent } from 'vue'
import type { SportLiveModuleScored, AllSport } from '~/composables/match/sport/sportModuleRegistry'
import { useMatchScoreEntry } from '~/composables/match/useMatchScoreEntry'

const scoredModule: SportLiveModuleScored = {
  // sport は FIGURE_SKATING / GYMNASTICS 両方で同一インスタンスを共有するため
  // 代表値として FIGURE_SKATING を設定（レジストリの動的 import 終端として機能する）。
  // 実際の競技（表示ラベル）は createScoreEntry の引数で受ける（live.vue が match.sport を渡す）。
  sport: 'FIGURE_SKATING',
  stateModel: 'SCORED',
  createScoreEntry(sport?: AllSport) {
    // フィギュア/体操以外が来た場合は表示ラベルの代表値（FIGURE_SKATING）へフォールバック。
    const scoredSport = sport === 'GYMNASTICS' ? 'GYMNASTICS' : 'FIGURE_SKATING'
    return useMatchScoreEntry({ sport: scoredSport })
  },
  eventSheet: defineAsyncComponent(
    () => import('~/components/match/MatchEventSheetScored.vue'),
  ),
}

export default scoredModule
