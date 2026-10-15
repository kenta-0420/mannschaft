// localStorage is not available on the server, so skip auth check during SSR.
// The auth.client.ts plugin restores tokens from localStorage before this runs client-side.
export default defineNuxtRouteMiddleware((to) => {
  if (import.meta.server) return

  const authStore = useAuthStore()
  if (!authStore.isAuthenticated) {
    return navigateTo({
      path: '/login',
      query: { redirect: to.fullPath },
    })
  }
})
