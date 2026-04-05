// Client-side auth check. Runs after auth.client.ts plugin has restored tokens from localStorage.
export default defineNuxtRouteMiddleware((to) => {
  const authStore = useAuthStore()
  if (!authStore.isAuthenticated) {
    return navigateTo({
      path: '/login',
      query: { redirect: to.fullPath },
    })
  }
})
