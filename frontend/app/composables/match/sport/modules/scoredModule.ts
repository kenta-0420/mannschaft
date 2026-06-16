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
 * 【MVP スコープ】合計点直接入力（2者対戦）。useMatchScoreEntry で状態管理＋
 *   PUT /scored-result の API 呼び出しを行い、MatchEventSheetScored.vue が入力 UI を担う。
 * 【後段 Phase（#1566 BE 済）】審判別/種目別採点内訳（match_scored_components・§4B）。
 *   useMatchScoredComponents で内訳行を管理し、PUT /scored-components（全置換）で送ると
 *   サーバーが side 別に符号付き集計して合計点を再導出する（二層正本・§4B.2）。
 *   MatchScoredComponentSheet.vue が内訳入力 UI を担い、MatchEventSheetScored.vue が
 *   「直接入力 ↔ 内訳入力」のモード切替を持つ（§8 の両立 UX）。
 * 【別波】多人数順位制（match_score_entries・§5B）は本モジュールでは扱わない。
 */
import { defineAsyncComponent } from 'vue'
import type { SportLiveModuleScored, AllSport } from '~/composables/match/sport/sportModuleRegistry'
import { useMatchScoreEntry } from '~/composables/match/useMatchScoreEntry'
import { useMatchScoredComponents } from '~/composables/match/useMatchScoredComponents'

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
  createComponentEntry(sport?: AllSport) {
    // 内訳トラッカー側は競技別カタログ（項目/種目の選択肢）を sport で出し分ける。
    const scoredSport = sport === 'GYMNASTICS' ? 'GYMNASTICS' : 'FIGURE_SKATING'
    return useMatchScoredComponents({ sport: scoredSport })
  },
  eventSheet: defineAsyncComponent(
    () => import('~/components/match/MatchEventSheetScored.vue'),
  ),
}

export default scoredModule
