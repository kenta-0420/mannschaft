import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createApp } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import Toast from 'primevue/toast'
import ToastService from 'primevue/toastservice'
import type { ToastServiceMethods } from 'primevue/toastservice'
import type { RouteLocationNormalized } from 'vue-router'
import systemAdminMiddleware from '~/middleware/system-admin'

const mockNavigateTo = vi.fn()
mockNuxtImport('navigateTo', () => (...args: unknown[]) => mockNavigateTo(...args))
mockNuxtImport('useAuthStore', () => () => ({ isAuthenticated: true, isSystemAdmin: false }))

const route = { path: '/system-admin/teams/deleted', fullPath: '/system-admin/teams/deleted' } as RouteLocationNormalized

// spyへの差し替えを行うsystem-admin.spec.tsとは分離し、Nuxtが提供する実ToastServiceを使う。
describe('system-admin 拒否通知の実表示', () => {
  let nuxtApp: ReturnType<typeof useNuxtApp>
  let originalHydrating: boolean
  let originalToastAdd: ToastServiceMethods['add'] | undefined
  let toastHost: HTMLDivElement
  let unmountToast: (() => void) | undefined

  beforeEach(() => {
    nuxtApp = useNuxtApp()
    originalHydrating = nuxtApp.isHydrating
    // 他specが共有providerのaddをspyへ差し替えていても、実EventBusで表示を検証する。
    const toastApp = createApp({ render: () => null })
    toastApp.use(ToastService)
    const realToast = toastApp.config.globalProperties.$toast
    originalToastAdd = nuxtApp.$toast?.add
    if (nuxtApp.$toast) {
      nuxtApp.$toast.add = realToast.add
    }
    else {
      nuxtApp.provide('toast', realToast)
    }
    toastHost = document.createElement('div')
    document.body.appendChild(toastHost)
  })

  afterEach(async () => {
    unmountToast?.()
    unmountToast = undefined
    await flushPromises()
    toastHost.remove()
    nuxtApp.isHydrating = originalHydrating
    nuxtApp.$toast.removeAllGroups()
    if (originalToastAdd) nuxtApp.$toast.add = originalToastAdd
    mockNavigateTo.mockReset()
  })

  it('マウント済み画面の管理者ガード拒否を実Toastに表示する', async () => {
    nuxtApp.isHydrating = false
    const wrapper = await mountSuspended(Toast, { attachTo: toastHost })
    unmountToast = () => wrapper.unmount()

    await systemAdminMiddleware(route, route)
    await flushPromises()

    expect(mockNavigateTo).toHaveBeenCalledWith('/dashboard')
    const expected = nuxtApp.$i18n.t('adminConsole.middleware.accessDeniedBody')
    expect(wrapper.findComponent({ name: 'ToastMessage' }).exists()).toBe(true)
    expect(document.querySelector('[role="alert"]')?.textContent).toContain(expected)
  })

  it('初期ガードで拒否してから実Toastをマウントしても拒否理由が画面に表示される', async () => {
    // 初回clientガードはapp.vueのToastがマウントする前に走る。
    expect(document.querySelector('[role="alert"]')).toBeNull()
    nuxtApp.isHydrating = true
    await systemAdminMiddleware(route, route)
    expect(mockNavigateTo).toHaveBeenCalledWith('/dashboard')

    const wrapper = await mountSuspended(Toast, { attachTo: toastHost })
    unmountToast = () => wrapper.unmount()
    nuxtApp.isHydrating = false
    await nuxtApp.callHook('app:mounted', nuxtApp.vueApp)
    await nuxtApp.callHook('app:suspense:resolve')
    await flushPromises()

    const expected = nuxtApp.$i18n.t('adminConsole.middleware.accessDeniedBody')
    await vi.waitFor(() => {
      expect(document.querySelector('[role="alert"]')?.textContent ?? '').toContain(expected)
    })
  })
})
