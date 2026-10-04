// Client refresh proof uses the existing Nuxt/happy-dom environment.
// Actual Pinia/logout/refresh/ofetch hooks; only external transport and browser integration boundaries are mocked.
import { beforeEach, describe, expect, it, vi } from 'vitest'
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
const { useApi, performTokenRefresh } = await import('~/composables/useApi')
const user = (id: number) => ({ id, email: 'synthetic@example.invalid', fullName: 'Synthetic', profileImageUrl: null })
function deferred<T>() {
 let resolve: ((value: T) => void) | undefined
 const promise = new Promise<T>(done => { resolve = done })
 return { promise, resolve: (value: T) => resolve?.(value) }
}
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
beforeEach(() => {
 setActivePinia(createPinia())
 external.fetch.mockReset(); external.navigate.mockReset(); external.report.mockReset()
})
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
})