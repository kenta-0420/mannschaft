export default defineNuxtPlugin(() => {
  const store = useNavSettingsStore()
  const authStore = useAuthStore()

  store.loadFromStorage()

  if (authStore.isAuthenticated) {
    void store.loadFromServer().catch((error) => {
      console.error('[navSettings] 起動時の設定取得に失敗しました', error)
    })
  }
})
