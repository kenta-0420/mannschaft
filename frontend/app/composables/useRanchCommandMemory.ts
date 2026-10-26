// 再送状態はNuxtAppのメモリだけに保持し、本人切替とApp破棄で旧runを無効化する。
import { ref, shallowRef, watch, type Ref, type ShallowRef } from 'vue'
import type { RanchCommandSnapshot } from './useRanchCommand'
export type RanchCommandScope = 'ranch' | 'diagnosis' | 'birth-profile' | 'reflection-recall' | 'ranch-admin'
export interface RanchCommandRun { snapshot: RanchCommandSnapshot; controller: AbortController }
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
// 各NuxtApp pluginで一度factoryを呼ぶ。module共有/useState/payload/storageなし。
export function createRanchCommandMemory(account: () => number | null): RanchCommandMemory {
 const accountId = ref(account())
 const generation = ref(0)
 const scopes = { ranch: createScope(), diagnosis: createScope(), 'birth-profile': createScope(), 'reflection-recall': createScope(), 'ranch-admin': createScope() }
 let disposed = false
 function invalidate(value: number | null) {
  const oldRuns = Object.values(scopes).map(state => state.activeRun)
  generation.value += 1
  accountId.value = value
  // abortの同期listenerが呼ばれる前に、すべてのscope参照を破棄する。
  for (const state of Object.values(scopes)) {
   state.pending.value = null
   state.running.value = false
   state.activeRun = null
  }
  for (const run of oldRuns) run?.controller.abort()
 }
 const stopAccount = watch(account, value => {
  if (!disposed && accountId.value !== value) invalidate(value)
 }, { flush: 'sync' })
 function dispose() {
  if (disposed) return
  disposed = true
  stopAccount()
  invalidate(null)
 }
 return { accountId, generation, scopes, dispose }
}
// plugin providerはmemory objectをreactive/deepproxy化しない（Ref identityはそのまま）。
// 初期authnullのcomposableは最初のauthenticated ownerを同期watchで一度だけcapture、確立後変更はstale。
// send(snapshot, signal)のdispatch前にもsamebindingチェック。
// success/catch: accountId+generation+activeRunidentity+pendingidentity。
// finally: accountId+generation+activeRunidentity（成功pending=null後の解除を許容）。
// discardRejected: binding一致+runningfalseでのみpendingclear。
// watcher寿命はcomponentunmountへ依存させない。NuxtApp cleanupでdispose。
// abortは到達済servercommitの取消保証ではない。通信不確定ならsamekey/body再送。
