// 同じNuxtAppで再送を共有し、最初の認証済み本人から切り替わった旧画面の命令を拒否する。
import { useNuxtApp } from '#app'
import { watch } from 'vue'
import type { RanchCommandScope } from './useRanchCommandMemory'
export interface RanchCommandSnapshot {
 path: string
 method: 'POST' | 'PUT' | 'DELETE'
 body?: unknown
 version?: string
 key: string
}
export function useRanchCommand(scope: RanchCommandScope = 'ranch') {
 const memory = useNuxtApp().$ranchCommandMemory
 const state = memory.scopes[scope]
 const owner = () => memory.accountId.value === null ? null : { id: memory.accountId.value, generation: memory.generation.value }
 let binding = owner()
 let stopFirstAccount: (() => void) | undefined
 if (!binding) stopFirstAccount = watch(memory.accountId, () => {
  binding ??= owner()
  if (binding) stopFirstAccount?.()
 }, { flush: 'sync' })
 const bindingCurrent = () => !!binding && memory.accountId.value === binding.id && memory.generation.value === binding.generation
 async function execute<T>(
  input: Omit<RanchCommandSnapshot, 'key'>,
  send: (snapshot: RanchCommandSnapshot, signal: AbortSignal) => Promise<T>,
 ): Promise<T> {
  if (memory.accountId.value === null) throw new Error('COMMAND_NOT_AUTHENTICATED')
  if (!bindingCurrent()) throw new Error('COMMAND_ACCOUNT_CHANGED')
  if (state.running.value) throw new Error('COMMAND_BUSY')
  const snapshot: RanchCommandSnapshot = state.pending.value ?? {
   ...JSON.parse(JSON.stringify(input)) as Omit<RanchCommandSnapshot, 'key'>,
   key: crypto.randomUUID(),
  }
  if (JSON.stringify({ ...snapshot, key: undefined }) !== JSON.stringify({ ...input, key: undefined })) throw new Error('COMMAND_RETRY_REQUIRED')
  const run = { snapshot, controller: new AbortController() }
  state.pending.value = snapshot
  state.running.value = true
  state.activeRun = run
  const currentRun = () => bindingCurrent() && state.activeRun === run
  const currentSnapshot = () => currentRun() && state.pending.value === snapshot
  try {
   if (!currentSnapshot()) throw new Error('COMMAND_ACCOUNT_CHANGED')
   const result = await send(snapshot, run.controller.signal)
   if (!currentSnapshot()) throw new Error('COMMAND_ACCOUNT_CHANGED')
   state.pending.value = null
   return result
  } catch (error) {
   if (!currentSnapshot()) throw new Error('COMMAND_ACCOUNT_CHANGED')
   throw error
  } finally {
   if (currentRun()) {
    state.running.value = false
    state.activeRun = null
   }
  }
 }
 // 旧画面の確定失敗が新accountのpendingを捨てない。
 function discardRejected() {
  if (bindingCurrent() && !state.running.value) state.pending.value = null
 }
 return { pending: state.pending, running: state.running, execute, discardRejected }
}
