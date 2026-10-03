import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { addRouteMiddleware, tryUseNuxtApp, useNuxtApp, useRouter } from '#app'
import { GATE_ROUTE_MAP } from '~/constants/featureGates'
import { useAuthStore } from '~/stores/useAuthStore'
import { useScopeStore } from '~/stores/useScopeStore'
import { useTeamStore } from '~/stores/useTeamStore'
import { useOrganizationStore } from '~/stores/useOrganizationStore'
import ReceiptsPage from '~/pages/admin/receipts.vue'

function deferred() {
  let resolve: () => void = () => {}
  const promise = new Promise<void>((finish) => { resolve = finish })
  return { promise, resolve }
}

let membershipGate: ReturnType<typeof deferred> | null = null
let middlewareGate: ReturnType<typeof deferred> | null = null
let middlewareEntered = false
let membershipRequested = false
const endpointCleanup: Array<() => void> = []

beforeAll(async () => {
  // 正規 useApi の HTTP 境界だけを提供する。router/store/guard は本物を使う。
  endpointCleanup.push(registerEndpoint('/api/v1/me/teams', async () => {
    membershipRequested = true
    await membershipGate?.promise
    return { data: [{ id: 12, slug: 'guard-team', name: '再現用チーム', role: 'MEMBER',
      nickname1: null, iconUrl: null, template: 'OTHER', memberCount: 1 }] }
  }))
  endpointCleanup.push(registerEndpoint('/api/v1/me/organizations', () => ({ data: [] })))
  endpointCleanup.push(registerEndpoint('/api/v1/admin/receipts', () => ({
    data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 },
  })))
  endpointCleanup.push(registerEndpoint('/api/v1/feature-flags', () => ({
    data: Object.keys(GATE_ROUTE_MAP).map(flagKey => ({ flagKey, enabled: true })),
  })))
  useAuthStore().user = { id: 1, email: 'guard@example.invalid', fullName: '再現利用者', profileImageUrl: null }
  useScopeStore().clear()
  const warmup = await mountSuspended(ReceiptsPage, { route: '/admin/receipts' })
  warmup.unmount()
  addRouteMiddleware('receipt-guard-deferred-navigation', async (to) => {
    if (to.path !== '/admin/receipts' || to.query.guardHold !== '1' || !middlewareGate) return
    middlewareEntered = true
    await middlewareGate.promise
  }, { global: true })
})

beforeEach(() => {
  membershipGate = deferred()
  middlewareGate = null
  middlewareEntered = false
  membershipRequested = false
  useScopeStore().clear()
  useTeamStore().clear()
  useOrganizationStore().clear()
  useAuthStore().user = { id: 1, email: 'guard@example.invalid', fullName: '再現利用者', profileImageUrl: null }
})

afterEach(() => {
  membershipGate?.resolve()
  middlewareGate?.resolve()
  membershipGate = null
  middlewareGate = null
  vi.restoreAllMocks()
})

afterAll(() => {
  for (const cleanup of endpointCleanup) cleanup()
})

describe('CMP1017: 非同期の所属拒否は実ルーターで終端画面へ移る', () => {
  it.each([false, true])('同一ページの middleware 待機=%s でも MEMBER を Dashboard へ戻す', async (holdMiddleware) => {
    const wrapper = await mountSuspended(ReceiptsPage, { route: '/admin/receipts' })
    const nuxtApp = useNuxtApp()
    const router = useRouter()
    const routeTrace: Array<Record<string, string | number | boolean>> = []
    const safePath = (value: string) => ['/admin/receipts', '/dashboard', '/login'].includes(value) ? value : '<other>'
    const componentNuxt = Reflect.get(wrapper.vm.$.appContext.app, '$nuxt') as typeof nuxtApp | undefined
    const componentRouter = componentNuxt?.$router
    routeTrace.push({
      kind: 'identity',
      componentNuxtMatches: componentNuxt === nuxtApp,
      componentRouterMatches: componentRouter === router,
      pageRouterMatches: wrapper.vm.$router === router,
      nuxtRouterMatches: nuxtApp.$router === router,
      client: import.meta.client, server: import.meta.server,
      mounted: wrapper.vm.$.isMounted,
    })
    const observerCleanup: Array<() => void> = []
    const actualRouters = new Set([router, wrapper.vm.$router, componentRouter].filter((value): value is typeof router => !!value))
    for (const actualRouter of actualRouters) {
      const measured = actualRouter === router
      const originalPush = actualRouter.push.bind(actualRouter)
      vi.spyOn(actualRouter, 'push').mockImplementation((to) => {
        routeTrace.push({ kind: 'push', measured, to: safePath(actualRouter.resolve(to).path), before: safePath(actualRouter.currentRoute.value.path), middleware: !!nuxtApp._processingMiddleware, client: import.meta.client, server: import.meta.server })
        const result = originalPush(to)
        // 同じ実Promiseを返す。観測側のreject分類は元Promise/awaitの例外を変更しない。
        void result.then((failure) => {
          routeTrace.push({ kind: 'push-resolved', measured, failureType: failure?.type ?? 0, after: safePath(actualRouter.currentRoute.value.path) })
        }, () => {
          routeTrace.push({ kind: 'push-rejected', measured, after: safePath(actualRouter.currentRoute.value.path) })
        })
        return result
      })
      observerCleanup.push(actualRouter.beforeEach((to, from) => {
        routeTrace.push({ kind: 'before', measured, to: safePath(to.path), from: safePath(from.path) })
      }))
      observerCleanup.push(actualRouter.afterEach((to, from, failure) => {
        routeTrace.push({ kind: 'after', measured, to: safePath(to.path), from: safePath(from.path), failureType: failure?.type ?? 0 })
      }))
      observerCleanup.push(actualRouter.onError(() => {
        routeTrace.push({ kind: 'router-error', measured, after: safePath(actualRouter.currentRoute.value.path) })
      }))
    }
    const toast = nuxtApp.$toast as { add: (options: Record<string, unknown>) => void }
    const originalAdd = toast.add.bind(toast)
    const observations: Array<{ contextMatches: boolean; middlewareActive: boolean }> = []
    vi.spyOn(toast, 'add').mockImplementation((options) => {
      if (options.summary === nuxtApp.$i18n.t('adminConsole.middleware.accessDeniedTitle')) {
        observations.push({
          contextMatches: tryUseNuxtApp() === nuxtApp,
          middlewareActive: !!nuxtApp._processingMiddleware,
        })
      }
      originalAdd(options)
    })

    let navigation: ReturnType<typeof router.push> | undefined
    try {
      if (holdMiddleware) {
        middlewareGate = deferred()
        // 遷移元と先を同じページにして、元の遷移自体による Dashboard 到達を除外する。
        navigation = router.push('/admin/receipts?guardHold=1')
        await expect.poll(() => middlewareEntered).toBe(true)
      }
      else {
        expect(!!nuxtApp._processingMiddleware).toBe(false)
      }
      useScopeStore().setTeamScope(12, '再現用チーム')
      await expect.poll(() => membershipRequested).toBe(true)
      membershipGate!.resolve()
      await expect.poll(() => observations.length).toBe(1)
      expect(observations).toEqual([{ contextMatches: true, middlewareActive: holdMiddleware }])
      console.info('receipt-guard-deny', observations[0])
      middlewareGate?.resolve()
      await navigation
      await flushPromises()
      await expect.poll(() => router.currentRoute.value.path).toBe('/dashboard')
    }
    finally {
      membershipGate?.resolve()
      middlewareGate?.resolve()
      try {
        await navigation
      }
      finally {
        try {
          console.info('receipt-router-observation', routeTrace)
          for (const cleanup of observerCleanup) cleanup()
        }
        finally {
          wrapper.unmount()
        }
      }
    }
  })
})
