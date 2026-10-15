/**
 * F08.10 フットサル競技モジュール（動的 import の終端・04 §G.16）。
 *
 * フットサルはサッカーと UX 骨格が同一（プリセット・連鎖・理由コード）。入力シートは
 * 共通の MatchEventSheet を流用し、タイマーのみフットサル用（前後半 20 分）を用いる。
 */
import { defineAsyncComponent } from 'vue'
import type { SportLiveModule, SportTimer } from '~/composables/match/sport/sportModuleRegistry'
import type { TimerState, UseMatchTimerCoreOptions } from '~/composables/match/sport/useMatchTimerCore'
import {
  useMatchTimerFutsal,
  isNextCompletedFutsal,
} from '~/composables/match/sport/useMatchTimerFutsal'

const futsalModule: SportLiveModule = {
  sport: 'FUTSAL',
  stateModel: 'CONTINUOUS_TIME',
  createTimer(options?: UseMatchTimerCoreOptions): SportTimer {
    return useMatchTimerFutsal(options)
  },
  eventSheet: defineAsyncComponent(
    () => import('~/components/match/MatchEventSheet.vue'),
  ),
  isNextCompleted(state: TimerState): boolean {
    return isNextCompletedFutsal(state)
  },
}

export default futsalModule
