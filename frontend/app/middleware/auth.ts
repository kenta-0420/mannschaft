// localStorage is not available on the server, so skip auth check during SSR.
// The auth.client.ts plugin restores tokens from localStorage before this runs client-side.
export default defineNuxtRouteMiddleware((to) => {
  if (import.meta.server) return

  const nuxtApp = useNuxtApp()
  const authStore = useAuthStore()
  if (!authStore.isAuthenticated) {
    // 初回SSRは異なるレイアウトのDOMをhydrateせず、ログイン文書へ置換する。
    const isInitialSsrNavigation = nuxtApp.isHydrating && nuxtApp.payload.serverRendered
    return navigateTo({
      path: '/login',
      query: { redirect: to.fullPath },
    }, {
      external: isInitialSsrNavigation,
      replace: isInitialSsrNavigation,
    })
  }
})
