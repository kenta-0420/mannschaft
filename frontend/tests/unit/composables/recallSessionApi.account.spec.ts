// @vitest-environment nuxt
// 実Pinia/NuxtApp命令メモリ/ofetchを使い、外部transportだけを遅延する。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia } from 'pinia'
import { effectScope } from 'vue'
import { useNuxtApp } from '#app'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import RecallPrivate from '~/components/reflections/ReflectionRecallSessionPrivate.vue'
import { useAuthStore } from '~/stores/useAuthStore'
import { useRanchCommand } from '~/composables/useRanchCommand'
import { useRecallSessionApi } from '~/composables/useRecallSessionApi'
const external = vi.hoisted(() => ({ fetch: vi.fn<typeof fetch>(), report: vi.fn() }))
vi.mock('ofetch', async importOriginal => {
 const original = await importOriginal<typeof import('ofetch')>()
 return { ...original, ofetch: original.ofetch.create({}, { fetch: external.fetch }) }
})
vi.mock('~/composables/useApiBaseUrl', () => ({ resolveApiBaseUrl: () => 'http://synthetic.invalid' }))
mockNuxtImport('useErrorReport', () => () => ({ capture: external.report, captureQuiet: external.report }))
mockNuxtImport('useProxyDeskStore', () => () => ({ isPinned: false }))
mockNuxtImport('useGuardianshipSwitchStore', () => () => ({ isActingAs: false }))
mockNuxtImport('useAdminImpersonationStore', () => () => ({ isImpersonating: false }))
const scopes: ReturnType<typeof effectScope>[] = []
const wrappers: { unmount: () => void }[] = []
async function factory() {
 const scope = effectScope(); scopes.push(scope)
 const api = await scope.run(() => useNuxtApp().runWithContext(() => useRecallSessionApi(useRanchCommand('reflection-recall'))))
 if (!api) throw new Error('RECALL_SCOPE_MISSING')
 return api
}
async function account(id: number) {
 const auth = useAuthStore()
 await auth.setUser({ id, email: 'synthetic@example.invalid', fullName: 'Synthetic', profileImageUrl: null })
 auth.setTokens(id === 1 ? 'A-access' : 'B-access', 'synthetic-refresh')
}
const entryId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const session = { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', status: 'STARTED', version: '0', prompts: [], answers: [], original: null }
const json = () => new Response(JSON.stringify({ data: session }), { headers: { 'Content-Type': 'application/json' } })
beforeEach(async () => {
 setActivePinia(useNuxtApp().$pinia)
 vi.spyOn(useAuthStore(), 'clearUserCaches').mockResolvedValue()
 await account(1)
 const api = await factory()
 api.command.discardRejected()
 external.fetch.mockReset(); external.report.mockReset()
})
afterEach(() => { for (const wrapper of wrappers.splice(0)) wrapper.unmount(); for (const scope of scopes.splice(0)) scope.stop(); vi.restoreAllMocks() })
describe('AR専用命令の本人と不確定再送', () => {
 it('通信不明だけ同じkey/bodyを保持し、成功応答dataを投影する', async () => {
  const keys: (string | null)[] = []
  external.fetch.mockImplementation(async (_request, options) => {
   keys.push(new Headers(options?.headers).get('Idempotency-Key'))
   if (keys.length === 1) throw new TypeError('SYNTHETIC_RESPONSE_LOST')
   return json()
  })
  const api = await factory()
  await expect(api.start(entryId)).rejects.toThrow()
  expect(api.command.pending.value?.body).toEqual({})
  expect(await api.retry()).toEqual(session)
  expect(keys).toHaveLength(2); expect(keys[0]).toBeTruthy(); expect(keys[1]).toBe(keys[0])
  expect(api.command.pending.value).toBeNull()
 })
 it('旧A確定409のcatchはBの未確定pendingを解除しない', async () => {
  let resolveA: ((value: Response) => void) | undefined
  external.fetch.mockImplementation(async (_request, options) => {
   if (new Headers(options?.headers).get('Authorization') === 'Bearer A-access') return new Promise<Response>(resolve => { resolveA = resolve })
   throw new TypeError('SYNTHETIC_B_RESPONSE_LOST')
  })
  const a = await factory()
  const settledA = a.start(entryId).then(() => 'UNEXPECTED_SUCCESS', error => error instanceof Error ? error.message : String(error))
  await vi.waitFor(() => { expect(resolveA).toBeDefined() }, { timeout: 1000 })
  if (!resolveA) throw new Error('A_TRANSPORT_NOT_DISPATCHED')
  await account(2)
  const b = await factory()
  await expect(b.start(entryId)).rejects.toThrow()
  const pendingB = b.command.pending.value
  expect(pendingB).not.toBeNull()
  resolveA(new Response(JSON.stringify({ error: { code: 'SYNTHETIC_REJECTED' } }), { status: 409, headers: { 'Content-Type': 'application/json' } }))
  expect(await settledA).toBe('COMMAND_ACCOUNT_CHANGED')
  expect(b.command.pending.value).toBe(pendingB); expect(b.command.running.value).toBe(false)
 })
 it('実ARフォームは空保存を無効にし、FORGOT一回答だけを保存する', async () => {
  const prompt = { id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', kind: 'TERM_CARD', heading: 'Synthetic cue', promptSide: 'TERM', promptText: 'SYNTHETIC_CUE_ONLY', maxAnswerLength: 200 }
  const initial = { ...session, prompts: [prompt] }
  let writes = 0
  const bodies: unknown[] = []
  external.fetch.mockImplementation(async (request, options) => {
   const path = new URL(String(request)).pathname
   if ((options?.method ?? 'GET') === 'GET' && path === '/api/v1/me/reflections/recall-sessions/' + session.id) return new Response(JSON.stringify({ data: initial }), { headers: { 'Content-Type': 'application/json' } })
   if (options?.method !== 'PUT' || path !== '/api/v1/me/reflections/recall-sessions/' + session.id + '/answers') throw new Error('UNEXPECTED_RECALL_TRANSPORT')
   writes += 1
   const body = JSON.parse(String(options.body))
   bodies.push(body)
   return new Response(JSON.stringify({ data: { ...initial, version: '1', answers: body.answers } }), { headers: { 'Content-Type': 'application/json' } })
  })
  const wrapper = await mountSuspended(RecallPrivate, { props: { entryId, sessionId: session.id } })
  wrappers.push(wrapper); await flushPromises()
  const saveLabel = useNuxtApp().$i18n.t('reflection.arSession.save')
  const button = () => wrapper.findAll('button').find(value => value.text() === saveLabel)
  const empty = button(); if (!empty) throw new Error('AR_SAVE_BUTTON_MISSING')
  expect(empty.attributes('disabled')).toBeDefined(); expect(writes).toBe(0)
  await wrapper.find('input[name="' + prompt.id + '"][value="FORGOT"]').setValue()
  const ready = button(); if (!ready) throw new Error('AR_SAVE_BUTTON_MISSING')
  expect(ready.attributes('disabled')).toBeUndefined()
  await ready.trigger('click'); await flushPromises()
  expect(writes).toBe(1); expect(bodies).toEqual([{ version: '0', answers: [{ promptId: prompt.id, state: 'FORGOT', text: null }] }]); expect(wrapper.text()).toContain('SYNTHETIC_CUE_ONLY')
 })

})
