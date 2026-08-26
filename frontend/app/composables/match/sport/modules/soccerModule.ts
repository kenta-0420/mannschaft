/**
 * F08.10 サッカー競技モジュール（動的 import の終端・04 §G.16）。
 *
 * sportModuleRegistry が `() => import('.../soccerModule')` で遅延読込する。本ファイルが
 * import するもの（サッカータイマー・共通入力シート）はサッカー試合を開いたときのみ
 * バンドルに読み込まれる（Vite コード分割）。
 *
 * default export が SportLiveModule。共通シェル（live.vue）はこの統一 IF だけを使う。
 */
import { defineAsyncComponent } from 'vue'
import type { SportLiveModule, SportTimer } from '~/composables/match/sport/sportModuleRegistry'
import type { TimerState, UseMatchTimerCoreOptions } from '~/composables/match/sport/useMatchTimerCore'
import {
  useMatchTimerSoccer,
  isNextCompletedSoccer,
} from '~/composables/match/sport/useMatchTimerSoccer'

const soccerModule: SportLiveModule = {
  sport: 'SOCCER',
  stateModel: 'CONTINUOUS_TIME',
  createTimer(options?: UseMatchTimerCoreOptions): SportTimer {
    return useMatchTimerSoccer(options)
  },
  // サッカーは既存の共通シート（得点/アシスト/カード/交代/その他）を遅延読込で使う。
  eventSheet: defineAsyncComponent(
    () => import('~/components/match/MatchEventSheet.vue'),
  ),
  isNextCompleted(state: TimerState): boolean {
    return isNextCompletedSoccer(state)
  },
}

export default soccerModule
