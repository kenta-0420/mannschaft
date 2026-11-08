// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createRanchCommandMemory } from './useRanchCommandMemory'
import { useRanchCommand, type RanchCommandSnapshot } from './useRanchCommand'
const app = vi.hoisted(() => ({ current: {} }))
vi.mock('#app', () => ({ useNuxtApp: () => app.current }))
let memory: ReturnType<typeof createRanchCommandMemory>
beforeEach(() => {
 memory = createRanchCommandMemory(() => 1)
 app.current = { $ranchCommandMemory: memory }
})
afterEach(() => memory.dispose())
describe('AC06/48/73 応答喪失のcommand再送', () => {
 it('通信失敗後、同key・同body・元versionのsnapshotを再送する', async () => {
  const command = useRanchCommand(); const sent: RanchCommandSnapshot[] = []
  const send = vi.fn(async snapshot => { sent.push(JSON.parse(JSON.stringify(snapshot))); if (sent.length === 1) throw new Error('network'); return { commandId:'server-id' } })
  const request = { path:'/api/v1/me/ranch/hatch', method:'POST' as const, body:{version:'7',name:'é',nameConfirmed:true} }
  await expect(command.execute(request,send)).rejects.toThrow('network')
  const originalKey = command.pending.value?.key
  expect(originalKey).toMatch(/^[0-9a-f-]{36}$/)
  await expect(command.execute(request,send)).resolves.toEqual({commandId:'server-id'})
  expect(sent[1]).toEqual(sent[0]); expect(command.pending.value).toBeNull()
 })
 it('失敗中に別bodyを新commandとして発行しない', async () => {
  const command = useRanchCommand(); const send = vi.fn(async () => {throw new Error('lost')})
  await expect(command.execute({path:'/x',method:'POST',body:{version:'1'}},send)).rejects.toThrow()
  await expect(command.execute({path:'/x',method:'POST',body:{version:'2'}},send)).rejects.toThrow('COMMAND_RETRY_REQUIRED')
  expect(send).toHaveBeenCalledTimes(1)
 })
 it('二重送信を防ぎ完了後だけ次のkeyを発行する', async () => {
  const command = useRanchCommand(); let resolve!: (value: string) => void
  const request = {path:'/x',method:'POST' as const,body:{}}
  const first = command.execute(request,() => new Promise<string>(done => {resolve=done}))
  await expect(command.execute(request,async () => 'second')).rejects.toThrow('COMMAND_BUSY')
  const firstKey = command.pending.value?.key; resolve('done'); await first
  let secondKey: string | undefined
  await command.execute(request,async snapshot => { secondKey=snapshot.key; return 'next' })
  expect(secondKey).not.toBe(firstKey)
 })
})

describe('AC06 remount後の本人command共有', () => {
 it('通信失敗後に再生成しても同key・body・versionを再送する', async () => {
  const request = { path: '/api/v1/me/ranch/hatch', method: 'POST' as const, body: { version: '7', name: 'é', nameConfirmed: true } }
  const first = useRanchCommand()
  await expect(first.execute(request, async () => { throw new Error('lost') })).rejects.toThrow('lost')
  const snapshot = first.pending.value
  const remounted = useRanchCommand()
  expect(remounted.pending.value).toEqual(snapshot)
  const send = vi.fn(async (value: RanchCommandSnapshot) => value.key)
  await remounted.execute(request, send)
  expect(send.mock.calls[0]?.[0]).toEqual(snapshot)
 })

 it('再生成した画面でも同domainの送信中commandを重ねない', async () => {
  const request = { path: '/api/v1/me/ranch/touch', method: 'POST' as const, body: { version: '7' } }
  const first = useRanchCommand()
  let resolve: ((value: string) => void) | undefined
  const running = first.execute(request, () => new Promise<string>(done => { resolve = done }))
  try {
   const remounted = useRanchCommand()
   expect(remounted.running.value).toBe(true)
   const duplicate = vi.fn(async () => 'duplicate')
   await expect(remounted.execute(request, duplicate)).rejects.toThrow('COMMAND_BUSY')
   expect(duplicate).not.toHaveBeenCalled()
  } finally {
   resolve?.('done')
   await running
  }
 })
})