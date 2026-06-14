/**
 * F08.10 バレーボール競技モジュール（動的 import の終端・04 §G.16 / sports/04_volleyball.md §8.5）。
 *
 * バレーはセット制（SET_BASED）のため、タイマーを持たず useMatchSetTracker を使う。
 * セット内点数ステッパー入力（主動線・§8.1）と自動デュース判定・セット確定 UI を提供する。
 * これらはバレー試合を開いたときのみ遅延読込される（Vite コード分割）。
 */
import { defineAsyncComponent } from 'vue'
import type { SportLiveModuleSetBased } from '~/composables/match/sport/sportModuleRegistry'
import { useMatchSetTracker } from '~/composables/match/sport/useMatchSetTracker'

const volleyballModule: SportLiveModuleSetBased = {
  sport: 'VOLLEYBALL',
  stateModel: 'SET_BASED',
  createSetTracker() {
    return useMatchSetTracker({ bestOf: 5 })
  },
  eventSheet: defineAsyncComponent(
    () => import('~/components/match/MatchEventSheetVolleyball.vue'),
  ),
}

export default volleyballModule
