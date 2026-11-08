// @vitest-environment node
// 本物のNuxtApp内factoryをprovider境界で接続し、本人切替を検証する。
import { ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createRanchCommandMemory } from './useRanchCommandMemory'
import { useRanchCommand } from './useRanchCommand'
const app = vi.hoisted(() => ({ current: {} }))
vi.mock('#app', () => ({ useNuxtApp: () => app.current }))
let account = ref<number | null>(1)
let memory: ReturnType<typeof createRanchCommandMemory>
beforeEach(() => {
 account = ref(1)
 memory = createRanchCommandMemory(() => account.value)
 app.current = { $ranchCommandMemory: memory }
})
afterEach(() => memory.dispose())
const input = { path: '/api/v1/me/ranch/touch', method: 'POST' as const, body: { version: '7' } }
function deferred<T>() {
 let resolve: ((value: T) => void) | undefined
 let reject: ((reason: unknown) => void) | undefined
 const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
 return { promise, resolve: (value: T) => resolve?.(value), reject: (reason: unknown) => reject?.(reason) }
}
describe('本人memoryのaccount境界', () => {
 it('未認証ではmutationを送信しない', async () => {
  account.value = null
  const send = vi.fn(async () => 'sent')
  await expect(useRanchCommand().execute(input, send)).rejects.toThrow('COMMAND_NOT_AUTHENTICATED')
  expect(send).not.toHaveBeenCalled()
 })
 it('初期未認証で作ったcomposableは通常auth復元後の初回commandを送信できる', async () => {
  account.value = null
  const command = useRanchCommand()
  account.value = 1
  await expect(command.execute(input, async () => 'ok')).resolves.toBe('ok')
 })
 it.each(['success', 'failure'] as const)('旧%sはBのpending/running/controllerを変更しない', async outcome => {
  const commandA = useRanchCommand()
  const responseA = deferred<string>()
  let signalA: AbortSignal | undefined
  const requestA = commandA.execute(input, (_snapshot, signal) => { signalA = signal; return responseA.promise })
  const rejectedA = expect(requestA).rejects.toThrow('COMMAND_ACCOUNT_CHANGED')
  account.value = null; account.value = 2
  const commandB = useRanchCommand()
  const responseB = deferred<string>()
  let signalB: AbortSignal | undefined
  const requestB = commandB.execute(input, (_snapshot, signal) => { signalB = signal; return responseB.promise })
  const pendingB = commandB.pending.value
  try {
   expect(signalA?.aborted).toBe(true)
   if (outcome === 'success') responseA.resolve('A-private-result')
   else responseA.reject(new Error('A-private-error'))
   await rejectedA
   expect(commandB.pending.value).toBe(pendingB)
   expect(commandB.running.value).toBe(true)
   expect(signalB?.aborted).toBe(false)
  } finally {
   responseA.resolve('A-cleanup')
   responseB.resolve('B-result')
   await Promise.allSettled([requestA, requestB, rejectedA])
  }
 })
 it('A logout後の同ID復帰でも旧generationの応答を拒否する', async () => {
  const old = deferred<string>()
  const request = useRanchCommand().execute(input, () => old.promise)
  const rejected = expect(request).rejects.toThrow('COMMAND_ACCOUNT_CHANGED')
  account.value = null; account.value = 1
  old.resolve('old-private-result')
  await rejected
 })
 it('旧instanceの確定失敗discardはB pendingを捨てない', async () => {
  const commandA = useRanchCommand()
  await expect(commandA.execute(input, async () => { throw new Error('lost-A') })).rejects.toThrow('lost-A')
  account.value = 2
  const commandB = useRanchCommand()
  await expect(commandB.execute(input, async () => { throw new Error('lost-B') })).rejects.toThrow('lost-B')
  const pendingB = commandB.pending.value
  commandA.discardRejected()
  expect(commandB.pending.value).toBe(pendingB)
  await expect(commandA.execute(input, async () => 'unexpected')).rejects.toThrow('COMMAND_ACCOUNT_CHANGED')
 })
 it('null起点でA復元後にBへ切り替わった旧画面は初回送信もしない', async () => {
  account.value = null
  const command = useRanchCommand()
  account.value = 1; account.value = 2
  const send = vi.fn(async () => 'unexpected')
  await expect(command.execute(input, send)).rejects.toThrow('COMMAND_ACCOUNT_CHANGED')
  expect(send).not.toHaveBeenCalled()
 })
 it('ranch/diagnosis/profileのrunningとpendingは独立する', async () => {
  const ranch = useRanchCommand('ranch')
  await expect(ranch.execute(input, async () => { throw new Error('lost') })).rejects.toThrow('lost')
  expect(useRanchCommand('diagnosis').pending.value).toBeNull()
  expect(useRanchCommand('birth-profile').pending.value).toBeNull()
 })
 it('別request用factoryは本人body/keyを共有しない', async () => {
  const first = useRanchCommand()
  await expect(first.execute(input, async () => { throw new Error('lost') })).rejects.toThrow('lost')
  const other = createRanchCommandMemory(() => 1)
  try {
   app.current = { $ranchCommandMemory: other }
   expect(useRanchCommand().pending.value).toBeNull()
   expect(other.scopes.ranch.pending).not.toBe(memory.scopes.ranch.pending)
  } finally { other.dispose() }
 })
 it('切替abortの同期listenerで開始したB別scopeを後続clearが壊さない', async () => {
  const responseA = deferred<string>()
  const responseB = deferred<string>()
  let requestB: Promise<string> | undefined
  let pendingB: typeof memory.scopes.diagnosis.pending.value = null
  let signalB: AbortSignal | undefined
  const requestA = useRanchCommand().execute(input, (_snapshot, signal) => {
   signal.addEventListener('abort', () => {
    const commandB = useRanchCommand('diagnosis')
    requestB = commandB.execute(input, (_body, nextSignal) => {
     signalB = nextSignal
     return responseB.promise
    })
    pendingB = commandB.pending.value
   }, { once: true })
   return responseA.promise
  })
  const rejectedA = expect(requestA).rejects.toThrow('COMMAND_ACCOUNT_CHANGED')
  try {
   account.value = 2
   expect(requestB).toBeDefined()
   expect(memory.scopes.diagnosis.pending.value).toBe(pendingB)
   expect(memory.scopes.diagnosis.running.value).toBe(true)
   expect(signalB?.aborted).toBe(false)
   responseA.resolve('old-A')
   await rejectedA
   responseB.resolve('current-B')
   await expect(requestB).resolves.toBe('current-B')
  } finally {
   responseA.resolve('cleanup-A'); responseB.resolve('cleanup-B')
   await Promise.allSettled([requestA, requestB, rejectedA])
  }
 })
 it('App disposeは旧runを無効化してabortし再入と二度目のdisposeは無害', async () => {
  const response = deferred<string>()
  let signal: AbortSignal | undefined
  const command = useRanchCommand()
  const request = command.execute(input, (_snapshot, currentSignal) => {
   signal = currentSignal
   currentSignal.addEventListener('abort', () => memory.dispose(), { once: true })
   return response.promise
  })
  const rejected = expect(request).rejects.toThrow('COMMAND_ACCOUNT_CHANGED')
  const previousGeneration = memory.generation.value
  try {
   memory.dispose()
   expect(memory.accountId.value).toBeNull()
   expect(memory.generation.value).toBe(previousGeneration + 1)
   expect(signal?.aborted).toBe(true)
   expect(command.pending.value).toBeNull()
   expect(command.running.value).toBe(false)
   expect(memory.scopes.ranch.activeRun).toBeNull()
   memory.dispose()
   expect(memory.generation.value).toBe(previousGeneration + 1)
   response.resolve('destroyed-App-private-result')
   await rejected
  } finally {
   response.resolve('cleanup')
   await Promise.allSettled([request, rejected])
  }
 })
})