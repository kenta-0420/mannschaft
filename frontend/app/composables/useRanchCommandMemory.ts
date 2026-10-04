import { ref, shallowRef, watch, type Ref, type ShallowRef } from 'vue'
import type { RanchCommandSnapshot } from './useRanchCommand'

export type RanchCommandScope = 'ranch' | 'diagnosis' | 'birth-profile'
export interface RanchCommandRun {
 snapshot: RanchCommandSnapshot
 controller: AbortController
}
export interface RanchCommandState {
 pending: ShallowRef<RanchCommandSnapshot | null>
 running: Ref<boolean>
 activeRun: RanchCommandRun | null
}
export interface RanchCommandMemory {
 accountId: Ref<number | null>
 generation: Ref<number>
 scopes: Record<RanchCommandScope, RanchCommandState>
 dispose: () => void
}
function createScope(): RanchCommandState {
 return { pending: shallowRef(null), running: ref(false), activeRun: null }
}
// NuxtAppごとに作成し、SSR request・payload・ブラウザstorageへ共有しない。
export function createRanchCommandMemory(account: () => number | null): RanchCommandMemory {
 const accountId = ref(account())
 const generation = ref(0)
 const scopes = { ranch: createScope(), diagnosis: createScope(), 'birth-profile': createScope() }
 const dispose = watch(account, value => {
  if (accountId.value === value) return
  generation.value += 1
  accountId.value = value
  for (const state of Object.values(scopes)) {
   const oldRun = state.activeRun
   state.pending.value = null
   state.running.value = false
   state.activeRun = null
   // 旧応答の同期callbackも新accountへ作用できないよう、参照を先に無効化する。
   oldRun?.controller.abort()
  }
 }, { flush: 'sync' })
 return { accountId, generation, scopes, dispose }
}