import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, reactive, ref, type Ref, type WritableComputedRef } from 'vue'
import {
  createMemoryHistory,
  createRouter,
  RouterView,
  type Router,
  type RouteLocationNormalizedLoaded,
} from 'vue-router'
import { useReservationTab } from './useReservationTab'

const mocks = vi.hoisted(() => ({
  route: null as unknown as RouteLocationNormalizedLoaded,
  router: null as unknown as Router,
}))
mockNuxtImport('useRoute', (original) => () => mocks.route ?? original())
mockNuxtImport('useRouter', (original) => () => mocks.router ?? original())

beforeEach(() => {
  mocks.route = reactive({
    path: '/teams/team-a/reservations',
    params: { slug: 'team-a' },
    query: { tab: 'manage' },
    hash: '',
  }) as unknown as RouteLocationNormalizedLoaded
})
afterEach(() => {
  mocks.route = null as unknown as RouteLocationNormalizedLoaded
  mocks.router = null as unknown as Router
})

async function mountPage(allowed: Ref<boolean>) {
  let activeTab!: WritableComputedRef<number>
  const reservationPage = defineComponent({
    setup() {
      activeTab = useReservationTab(allowed, ref(true))
      return () => h('div', String(activeTab.value))
    },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/teams/:slug/reservations', name: 'reservations', component: reservationPage },
      {
        path: '/teams/:slug/info',
        name: 'info',
        component: defineComponent({ render: () => h('div', 'info') }),
      },
    ],
  })
  mocks.router = router
  router.afterEach((to, _from, failure) => {
    if (!failure)
      Object.assign(mocks.route, {
        path: to.path,
        params: to.params,
        query: to.query,
        hash: to.hash,
      })
  })
  await router.push('/teams/team-a/reservations?tab=manage')
  const replace = vi.spyOn(router, 'replace')
  const wrapper = mount(defineComponent({ render: () => h(RouterView) }), {
    global: { plugins: [router] },
  })
  await flushPromises()
  return { router, replace, wrapper, activeTab }
}

describe('useReservationTab 実Routerの遷移境界', () => {
  it.each(['/teams/team-a/info', '/teams/team-b/reservations'])(
    'AC-8: %sへの非同期guard待機中に権限が失効しても旧ページへ書き戻さない',
    async (destination) => {
      const allowed = ref(true)
      const { router, replace, wrapper } = await mountPage(allowed)
      let release!: () => void
      let entered!: () => void
      const blocked = new Promise<void>((resolve) => {
        release = resolve
      })
      const started = new Promise<void>((resolve) => {
        entered = resolve
      })
      router.beforeResolve(async (to) => {
        if (to.path === destination) {
          entered()
          await blocked
        }
      })

      const navigation = router.push(destination)
      await started
      allowed.value = false
      await flushPromises()
      try {
        expect(replace, '遷移先のguardがawait中でも旧予約URLへreplaceしない').not.toHaveBeenCalled()
        release()
        await navigation
        expect(router.currentRoute.value.path).toBe(destination)
      } finally {
        release()
        await navigation
        wrapper.unmount()
      }
    },
  )

  it('AC-8: 古い遷移のCANCELLEDで新しい遷移待機中の抑止を解除しない', async () => {
    const allowed = ref(true)
    const { router, replace, wrapper } = await mountPage(allowed)
    let releaseFirst!: () => void
    let releaseSecond!: () => void
    let enterFirst!: () => void
    let enterSecond!: () => void
    const firstGate = new Promise<void>((resolve) => {
      releaseFirst = resolve
    })
    const secondGate = new Promise<void>((resolve) => {
      releaseSecond = resolve
    })
    const firstStarted = new Promise<void>((resolve) => {
      enterFirst = resolve
    })
    const secondStarted = new Promise<void>((resolve) => {
      enterSecond = resolve
    })
    router.beforeResolve(async (to) => {
      if (to.path === '/teams/team-a/info') {
        enterFirst()
        await firstGate
      } else if (to.path === '/teams/team-b/reservations') {
        enterSecond()
        await secondGate
      }
    })

    const firstNavigation = router.push('/teams/team-a/info')
    await firstStarted
    const secondNavigation = router.push('/teams/team-b/reservations')
    await secondStarted
    releaseFirst()
    await firstNavigation
    allowed.value = false
    await flushPromises()
    try {
      expect(replace, '新しい遷移のguard待機中は旧URLへreplaceしない').not.toHaveBeenCalled()
      releaseSecond()
      await secondNavigation
      expect(router.currentRoute.value.path).toBe('/teams/team-b/reservations')
    } finally {
      releaseSecond()
      await secondNavigation
      wrapper.unmount()
    }
  })

  it('AC-6/8: URL完了を待たない素早いタブ往復でも最後の選択を維持する', async () => {
    const { router, wrapper, activeTab } = await mountPage(ref(true))
    activeTab.value = 1
    expect(activeTab.value).toBe(1)
    activeTab.value = 2
    expect(activeTab.value).toBe(2)
    await flushPromises()

    expect(activeTab.value).toBe(2)
    expect(router.currentRoute.value.query.tab).toBe('manage')
    wrapper.unmount()
  })

  it('AC-6/8: query遷移が拒否されたらURLにある選択へ戻す', async () => {
    const { router, wrapper, activeTab } = await mountPage(ref(true))
    router.beforeResolve((to) => (to.query.tab === 'list' ? false : undefined))
    activeTab.value = 1
    expect(activeTab.value).toBe(1)
    await flushPromises()

    expect(router.currentRoute.value.query.tab).toBe('manage')
    expect(activeTab.value).toBe(2)
    wrapper.unmount()
  })

  it.each(['abort', 'error'])(
    'AC-8: 遷移が%sで停止した後は元画面のタブ操作を再開できる',
    async (failure) => {
      const { router, replace, wrapper, activeTab } = await mountPage(ref(true))
      router.onError(() => {})
      router.beforeResolve((to) => {
        if (to.name !== 'info') return
        if (failure === 'error') throw new Error('navigation-test-error')
        return false
      })
      if (failure === 'error') {
        await expect(router.push('/teams/team-a/info')).rejects.toThrow('navigation-test-error')
      } else {
        await router.push('/teams/team-a/info')
      }
      expect(router.currentRoute.value.path).toBe('/teams/team-a/reservations')

      activeTab.value = 1
      await flushPromises()
      expect(replace).toHaveBeenCalledOnce()
      expect(router.currentRoute.value.query.tab).toBe('list')
      wrapper.unmount()
    },
  )
})
