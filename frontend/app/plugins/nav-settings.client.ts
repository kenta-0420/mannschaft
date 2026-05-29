export default defineNuxtPlugin(async () => {
  const store = useNavSettingsStore()
  const authStore = useAuthStore()

  store.loadFromStorage()

  if (authStore.isAuthenticated) {
    await store.loadFromServer()
  }
})
