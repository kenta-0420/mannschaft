/**
 * 一般ユーザー向け公開フィーチャーフラグの起動時ロード（Gate基盤工事①）。
 * nav-settings.client.ts のパターンを踏襲。
 */
export default defineNuxtPlugin(async () => {
  const authStore = useAuthStore()

  if (authStore.isAuthenticated) {
    const store = useFeatureFlagStore()
    try {
      await store.loadPublicFlags()
    } catch (error) {
      // 握りつぶさず正直に出力する。ただしアプリ起動は妨げない。
      console.error('公開フィーチャーフラグの取得に失敗しました', error)
    }
  }
})
