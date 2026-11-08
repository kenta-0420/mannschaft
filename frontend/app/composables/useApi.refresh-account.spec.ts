// Client refresh proof uses the existing Nuxt/happy-dom environment.
// Actual Pinia/logout/refresh/ofetch hooks; only external transport and browser integration boundaries are mocked.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, watch } from 'vue'
import { getAuthSessionContext } from '~/composables/authSessionContext'
import { createPinia, setActivePinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useAuthStore as actualUseAuthStore } from '~/stores/useAuthStore'
const external = vi.hoisted(() => ({ fetch: vi.fn<typeof fetch>(), navigate: vi.fn(), chatClear: vi.fn(), report: vi.fn() }))
vi.mock('ofetch', async importOriginal => {
 const original = await importOriginal<typeof import('ofetch')>()
 return { ...original, ofetch: original.ofetch.create({}, { fetch: external.fetch }) }
})
vi.mock('~/composables/useApiBaseUrl', () => ({ resolveApiBaseUrl: () => 'http://synthetic.invalid' }))
mockNuxtImport('navigateTo', () => external.navigate)
mockNuxtImport('useChatTabsStore', () => () => ({ clearAll: external.chatClear }))
mockNuxtImport('useErrorReport', () => () => ({ capture: external.report }))
mockNuxtImport('useProxyDeskStore', () => () => ({ isPinned: false }))
mockNuxtImport('useGuardianshipSwitchStore', () => () => ({ isActingAs: false }))
mockNuxtImport('useAdminImpersonationStore', () => () => ({ isImpersonating: false }))
const { useApi, performTokenRefresh, armProactiveRefresh, disarmProactiveRefresh } = await import('~/composables/useApi')
const user = (id: number) => ({ id, email: 'synthetic@example.invalid', fullName: 'Synthetic', profileImageUrl: null })
function deferred<T>() {
 let resolve: ((value: T) => void) | undefined
 const promise = new Promise<T>(done => { resolve = done })
 return { promise, resolve: (value: T) => resolve?.(value) }
}
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
beforeEach(() => {
 setActivePinia(createPinia())
 vi.spyOn(actualUseAuthStore(), 'clearUserCaches').mockResolvedValue()
 external.fetch.mockReset(); external.navigate.mockReset(); external.report.mockReset()
})
afterEach(() => vi.restoreAllMocks())
describe('本人切替後の旧refresh副作用を遮断', () => {
 it('A成功応答がBtokenと本物onRequestのprivate headerを上書きしない', async () => {
  const auth = actualUseAuthStore()
  vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
  await auth.setUser(user(1)); auth.setTokens('A-original', 'A-refresh')
  const delayed = deferred<Response>()
  external.fetch.mockReturnValueOnce(delayed.promise)
  const old = performTokenRefresh(useRuntimeConfig(), auth)
  await auth.logout()
  await auth.setUser(user(2)); auth.setTokens('B-access', 'B-refresh')
  delayed.resolve(json({ data: { accessToken: 'A-delayed', refreshToken: 'A-refresh-delayed' } }))
  await old
  expect.soft(auth.user?.id).toBe(2)
  expect.soft(auth.accessToken).toBe('B-access')
  const headers: string[] = []
  external.fetch.mockImplementationOnce(async (_request, options) => {
   headers.push(new Headers(options?.headers).get('Authorization') ?? '')
   return json({ data: {} })
  })
  await useApi()('/api/v1/me/ranch')
  expect(headers).toEqual(['Bearer B-access'])
 })
 it('実401hookで開始したA refreshのauth_failedがBをlogoutしない', async () => {
  const auth = actualUseAuthStore()
  vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
  await auth.setUser(user(1)); auth.setTokens('A-original', 'A-refresh')
  const started = deferred<boolean>()
  const delayed = deferred<Response>()
  external.fetch.mockImplementation(async request => {
   if (String(request).endsWith('/api/v1/auth/refresh')) { started.resolve(true); return delayed.promise }
   return json({ error: { code: 'AUTH_007' } }, 401)
  })
  const old = useApi()('/api/v1/me/ranch')
  const rejected = expect(old).rejects.toThrow()
  await started.promise
  await auth.logout()
  await auth.setUser(user(2)); auth.setTokens('B-access', 'B-refresh')
  delayed.resolve(json({ error: { code: 'AUTH_007' } }, 401))
  await rejected
  expect.soft(auth.user?.id).toBe(2)
  expect.soft(auth.accessToken).toBe('B-access')
  expect(external.navigate).toHaveBeenCalledTimes(1)
 })
 it('実ofetchの旧401再送はrefresh成功後のB資格でdispatchしない', async () => {
  const auth = actualUseAuthStore()
  vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
  await auth.setUser(user(1)); auth.setTokens('A-original', 'A-refresh')
  localStorage.setItem('tokenExpiresAt', String(Date.now() + 15 * 60_000))
  const headers: string[] = []
  const stop = watch(() => auth.accessToken, token => {
   if (token === 'A-refresh-success') queueMicrotask(() => {
    void auth.setUser(user(2)); auth.setTokens('B-access', 'B-refresh')
   })
  }, { flush: 'sync' })
  external.fetch.mockImplementation(async (request, options) => {
   if (String(request).endsWith('/api/v1/auth/refresh')) return json({ data: { accessToken: 'A-refresh-success', refreshToken: 'A-refresh-new' } })
   headers.push(new Headers(options?.headers).get('Authorization') ?? '')
   return json({ error: { code: 'AUTH_007' } }, 401)
  })
  try {
   await expect(useApi()('/api/v1/me/ranch')).rejects.toThrow('AUTH_SESSION_CHANGED')
   expect(headers).toEqual(['Bearer A-original'])
   expect(auth.user?.id).toBe(2)
   expect(auth.accessToken).toBe('B-access')
  } finally { stop(); disarmProactiveRefresh(auth); localStorage.removeItem('tokenExpiresAt') }
 })
 it('別Pinia storeのrefresh flightを共有せず同storeだけdedupする', async () => {
  const authA = actualUseAuthStore()
  await authA.setUser(user(1)); authA.setTokens('A-original', 'A-refresh')
  setActivePinia(createPinia())
  const authB = actualUseAuthStore()
  vi.spyOn(authB, 'clearUserCaches').mockResolvedValue()
  await authB.setUser(user(2)); authB.setTokens('B-original', 'B-refresh')
  const responseA = deferred<Response>()
  const responseB = deferred<Response>()
  external.fetch.mockReturnValueOnce(responseA.promise).mockReturnValueOnce(responseB.promise)
  const first = performTokenRefresh(useRuntimeConfig(), authA)
  const same = performTokenRefresh(useRuntimeConfig(), authA)
  const second = performTokenRefresh(useRuntimeConfig(), authB)
  try {
   expect(first).toBe(same)
   expect(first).not.toBe(second)
   expect(external.fetch).toHaveBeenCalledTimes(2)
   responseA.resolve(json({ data: { accessToken: 'A-new', refreshToken: 'A-new-refresh' } }))
   responseB.resolve(json({ data: { accessToken: 'B-new', refreshToken: 'B-new-refresh' } }))
   await Promise.all([first, second])
   expect(authA.accessToken).toBe('A-new')
   expect(authB.accessToken).toBe('B-new')
  } finally {
   responseA.resolve(json({ data: { accessToken: 'A-cleanup', refreshToken: 'A-cleanup-refresh' } }))
   responseB.resolve(json({ data: { accessToken: 'B-cleanup', refreshToken: 'B-cleanup-refresh' } }))
   await Promise.allSettled([first, second])
   getAuthSessionContext(authA).dispose(); getAuthSessionContext(authB).dispose()
  }
 })
 it('独立VueApp破棄はwatchと旧flightを止め二重disposeでも旧応答を適用しない', async () => {
  const auth = actualUseAuthStore()
  await auth.setUser(user(1)); auth.setTokens('A-original', 'A-refresh')
  const session = getAuthSessionContext(auth)
  const app = createApp({ render: () => null })
  const host = document.createElement('div')
  session.bindApp({ vueApp: app })
  app.mount(host)
  const delayed = deferred<Response>()
  external.fetch.mockReturnValueOnce(delayed.promise)
  const request = performTokenRefresh(useRuntimeConfig(), auth)
  const signal = session.refreshFlight?.controller.signal
  try {
   app.unmount(); session.dispose()
   expect(signal?.aborted).toBe(true)
   expect(session.refreshFlight).toBeNull()
   const generation = session.generation
   await auth.setUser(user(2)); auth.setTokens('B-access', 'B-refresh')
   expect(session.generation).toBe(generation)
   delayed.resolve(json({ data: { accessToken: 'A-late', refreshToken: 'A-late-refresh' } }))
   await expect(request).resolves.toBe('session_changed')
   expect(auth.accessToken).toBe('B-access')
  } finally {
   delayed.resolve(json({ data: { accessToken: 'A-cleanup', refreshToken: 'A-cleanup-refresh' } }))
   await Promise.allSettled([request]); host.remove()
  }
 })
 it('世代bound timerの捕捉済み旧callbackは本人切替後にHTTPを開始しない', async () => {
  // callbackを手動再入する予防境界。実ブラウザのtimer queue raceの証明とは分ける。
  const auth = actualUseAuthStore()
  await auth.setUser(user(1)); auth.setTokens('A-original', 'A-refresh')
  localStorage.setItem('tokenExpiresAt', String(Date.now() + 15 * 60_000))
  const timer = vi.spyOn(globalThis, 'setTimeout')
  try {
   armProactiveRefresh(useRuntimeConfig(), auth)
   const callback = timer.mock.calls[0]?.[0]
   if (typeof callback !== 'function') throw new Error('PROACTIVE_CALLBACK_NOT_CAPTURED')
   await auth.setUser(user(2)); auth.setTokens('B-access', 'B-refresh')
   callback()
   expect(external.fetch).not.toHaveBeenCalled()
  } finally {
   disarmProactiveRefresh(auth)
   for (const result of timer.mock.results) if (result.type === 'return') clearTimeout(result.value)
   timer.mockRestore(); localStorage.removeItem('tokenExpiresAt')
  }
 })
 it('修正したlogin順のtokens→await setUser→armで本人proactive timerを維持する', async () => {
  const auth = actualUseAuthStore()
  const previousExpiry = localStorage.getItem('tokenExpiresAt')
  auth.setTokens('LOGIN-access', 'LOGIN-refresh')
  localStorage.setItem('tokenExpiresAt', String(Date.now() + 15 * 60 * 1000))
  const session = getAuthSessionContext(auth)
  try {
   // 修正したcaller順の回帰。本物store/context/armを通し、LoginPage全体の走行とは分ける。
   await auth.setUser(user(3))
   armProactiveRefresh(useRuntimeConfig(), auth)
   expect(session.accountId).toBe(3)
   expect(session.proactiveTimer).not.toBeNull()
   expect(external.fetch).not.toHaveBeenCalled()
  } finally {
   disarmProactiveRefresh(auth)
   session.dispose()
   if (previousExpiry === null) localStorage.removeItem('tokenExpiresAt')
   else localStorage.setItem('tokenExpiresAt', previousExpiry)
  }
 })})
