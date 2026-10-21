import { effectScope, watch } from 'vue'
import type { useAuthStore } from '~/stores/useAuthStore'
import type { TokenRefreshResult } from '~/composables/useApi'

type AuthStore = ReturnType<typeof useAuthStore>
export interface AuthSessionSnapshot { accountId: number | null; generation: number }
export interface AuthRefreshFlight {
 snapshot: AuthSessionSnapshot
 controller: AbortController
 promise: Promise<TokenRefreshResult>
}
export interface AuthSessionContext {
 accountId: number | null
 generation: number
 disposed: boolean
 refreshFlight: AuthRefreshFlight | null
 proactiveTimer: ReturnType<typeof setTimeout> | null
 logoutFlight: { snapshot: AuthSessionSnapshot; promise: Promise<void> } | null
 capture: () => AuthSessionSnapshot
 current: (snapshot: AuthSessionSnapshot) => boolean
 dispose: () => void
 bindApp: (app: { vueApp: { onUnmount: (cleanup: () => void) => unknown }; hook?: (name: 'app:rendered' | 'app:error', cleanup: () => void) => unknown }) => void
}

// keyはPinia store実体。別NuxtApp/SSR requestの別storeへPromiseやtimerを共有しない。
const contexts = new WeakMap<AuthStore, AuthSessionContext>()
export function getAuthSessionContext(authStore: AuthStore): AuthSessionContext {
 const existing = contexts.get(authStore)
 if (existing) return existing
 // component scopeを離れて動作するが、App破棄で明示的に止める。
 const scope = effectScope(true)
 const boundApps = new WeakSet<object>()
 const context: AuthSessionContext = {
  accountId: authStore.user?.id ?? null,
  generation: 0,
  disposed: false,
  refreshFlight: null,
  proactiveTimer: null,
  logoutFlight: null,
  capture: () => ({ accountId: context.accountId, generation: context.generation }),
  current: snapshot => !context.disposed && context.accountId === snapshot.accountId && context.generation === snapshot.generation && (authStore.user?.id ?? null) === snapshot.accountId,
  dispose: () => {
   if (context.disposed) return
   context.disposed = true
   scope.stop()
   invalidate(null)
  },
  bindApp: app => {
   if (boundApps.has(app)) return
   boundApps.add(app)
   app.vueApp.onUnmount(context.dispose)
   if (import.meta.server) {
    if (!app.hook) throw new Error('AUTH_SESSION_LIFETIME_HOOK_MISSING')
    app.hook('app:rendered', context.dispose)
    app.hook('app:error', context.dispose)
   }
  },
 }
 function invalidate(accountId: number | null) {
  const previous = context.refreshFlight
  const timer = context.proactiveTimer
  context.generation += 1
  context.accountId = accountId
  context.refreshFlight = null
  context.proactiveTimer = null
  context.logoutFlight = null
  if (timer !== null) clearTimeout(timer)
  previous?.controller.abort()
 }
 contexts.set(authStore, context)
 scope.run(() => watch(() => authStore.user?.id ?? null, id => invalidate(id), { flush: 'sync' }))
 return context
}
