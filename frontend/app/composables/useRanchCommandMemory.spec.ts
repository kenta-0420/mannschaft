// Nuxt client試験環境で別Appのallocationを検証する。実server-transform SSR全体の証明とは分ける。
import { afterEach, describe, expect, it } from 'vitest'
import { createSSRApp } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import type { NuxtApp } from '#app'
import plugin from '../plugins/ranch-command-memory'
import { useAuthStore } from '../stores/useAuthStore'
import type { RanchCommandMemory } from './useRanchCommandMemory'

const memories: RanchCommandMemory[] = []
afterEach(() => { for (const memory of memories.splice(0)) memory.dispose() })
const user = (id: number) => ({ id, email: 'synthetic@example.invalid', fullName: 'Synthetic', profileImageUrl: null })
async function requestApp(id: number) {
 setActivePinia(createPinia())
 const auth = useAuthStore()
 await auth.setUser(user(id))
 const app = { vueApp: createSSRApp({ render: () => null }), payload: { data: {}, state: {} } }
 // Nuxt pluginを別NuxtAppの最小境界で実行する。factoryは実物を使用する。
 const provided = await plugin(app as unknown as NuxtApp)
 if (!provided?.provide?.ranchCommandMemory) throw new Error('MISSING_MEMORY_PROVIDER')
 const memory = provided.provide.ranchCommandMemory
 memories.push(memory)
 return { auth, app, memory }
}
describe('AC06 本人commandのNuxtApp内memory', () => {
 it('別NuxtAppのallocationへ本人body・keyを共有しない', async () => {
  const first = await requestApp(1)
  first.memory.scopes['birth-profile'].pending.value = {
   path: '/api/v1/me/birth-profile', method: 'PUT', key: 'synthetic-key',
   body: { firstName: 'SyntheticA', birthDate: '2000-01-01', revision: '7' },
  }
  const second = await requestApp(2)
  expect(first.memory).not.toBe(second.memory)
  expect(second.memory.accountId.value).toBe(2)
  expect(second.memory.scopes['birth-profile'].pending.value).toBeNull()
  expect(JSON.stringify(first.app.payload)).toBe('{"data":{},"state":{}}')
  expect(JSON.stringify(second.app.payload)).not.toContain('SyntheticA')
 })
 it('切替はscopeを破棄して旧runだけabortする', async () => {
  const { auth, memory } = await requestApp(1)
  const snapshot = { path: '/api/v1/me/ranch/touch', method: 'POST' as const, key: 'synthetic-key' }
  const controller = new AbortController()
  const state = memory.scopes.ranch
  state.pending.value = snapshot
  state.running.value = true
  state.activeRun = { snapshot, controller }
  const before = memory.generation.value
  await auth.setUser(user(2))
  expect(memory.generation.value).toBe(before + 1)
  expect(controller.signal.aborted).toBe(true)
  expect(state.pending.value).toBeNull()
  expect(state.running.value).toBe(false)
  expect(state.activeRun).toBeNull()
 })
})