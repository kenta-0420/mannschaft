/**
 * F08.10 バスケットボール競技モジュール（動的 import の終端・04 §G.16）。
 *
 * バスケは得点種別（2P/3P/FT）・ファウル体系がサッカーと根本的に異なるため、
 * 専用の入力シート（MatchEventSheetBasketball）と専用タイマー（4 クォーター＋OT）を用いる。
 * これらはバスケ試合を開いたときのみ遅延読込される（Vite コード分割）。
 */
import { defineAsyncComponent } from 'vue'
import type { SportLiveModule, SportTimer } from '~/composables/match/sport/sportModuleRegistry'
import type { TimerState, UseMatchTimerCoreOptions } from '~/composables/match/sport/useMatchTimerCore'
import {
  useMatchTimerBasketball,
  isNextCompletedBasketball,
} from '~/composables/match/sport/useMatchTimerBasketball'

const basketballModule: SportLiveModule = {
  sport: 'BASKETBALL',
  stateModel: 'CONTINUOUS_TIME',
  createTimer(options?: UseMatchTimerCoreOptions): SportTimer {
    return useMatchTimerBasketball(options)
  },
  eventSheet: defineAsyncComponent(
    () => import('~/components/match/MatchEventSheetBasketball.vue'),
  ),
  isNextCompleted(state: TimerState): boolean {
    return isNextCompletedBasketball(state)
  },
}

export default basketballModule
