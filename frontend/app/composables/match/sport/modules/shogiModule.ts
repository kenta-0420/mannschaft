/**
 * F08.10 将棋競技モジュール（動的 import の終端・04 §G.16 / sports/05_shogi.md §8.5）。
 *
 * 将棋はターン制（TURN_BASED）のため、タイマー・セットトラッカーを持たず
 * useMatchTurnTracker を使う。
 * 対局結果の最小入力（勝者選択・勝ち方・手数任意・局面写真）UI を提供する。
 * これらは将棋試合を開いたときのみ遅延読込される（Vite コード分割）。
 *
 * SHOGI は OpenAPI 再生成で生成型 Sport へ統合済み（AllSport=Sport・前向きユニオン返済済み）。
 */
import { defineAsyncComponent } from 'vue'
import type { SportLiveModuleTurnBased } from '~/composables/match/sport/sportModuleRegistry'
import { useMatchTurnTracker } from '~/composables/match/sport/useMatchTurnTracker'

const shogiModule: SportLiveModuleTurnBased = {
  sport: 'SHOGI',
  stateModel: 'TURN_BASED',
  createTurnTracker() {
    return useMatchTurnTracker({ sport: 'SHOGI' })
  },
  eventSheet: defineAsyncComponent(
    () => import('~/components/match/MatchEventSheetTurnBased.vue'),
  ),
}

export default shogiModule
