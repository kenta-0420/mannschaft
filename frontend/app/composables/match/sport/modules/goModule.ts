/**
 * F08.10 囲碁競技モジュール（動的 import の終端・04 §G.16 / sports/06_go.md §8.5）。
 *
 * 囲碁はターン制（TURN_BASED）のため、将棋（shogiModule）と同じ useMatchTurnTracker を共用する。
 * 差分は sport='GO' のみ（目数差 margin 入力が有効化される）。
 * 対局結果の最小入力（勝者選択・勝ち方・手数任意・目数差任意・局面写真）UI を提供する。
 * これらは囲碁試合を開いたときのみ遅延読込される（Vite コード分割）。
 *
 * GO は OpenAPI 再生成で生成型 Sport へ統合済み（AllSport=Sport・前向きユニオン返済済み）。
 */
import { defineAsyncComponent } from 'vue'
import type { SportLiveModuleTurnBased } from '~/composables/match/sport/sportModuleRegistry'
import { useMatchTurnTracker } from '~/composables/match/sport/useMatchTurnTracker'

const goModule: SportLiveModuleTurnBased = {
  sport: 'GO',
  stateModel: 'TURN_BASED',
  createTurnTracker() {
    return useMatchTurnTracker({ sport: 'GO' })
  },
  eventSheet: defineAsyncComponent(
    () => import('~/components/match/MatchEventSheetTurnBased.vue'),
  ),
}

export default goModule
