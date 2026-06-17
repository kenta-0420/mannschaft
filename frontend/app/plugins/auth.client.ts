export default defineNuxtPlugin(async () => {
  const authStore = useAuthStore()
  authStore.loadFromStorage()

  if (authStore.isAuthenticated) {
    const raw = localStorage.getItem('tokenExpiresAt')
    const expiresAt = raw ? Number(raw) : 0
    const SKEW_MS = 30_000 // 時計ズレ・往復遅延の安全マージン
    // access_token Cookie が失効済み（or 期限不明）の場合のみ、認証付きAPIを撃つ前に先回り更新。
    // 有効期限内のリロードでは更新しない＝無駄な往復ゼロ。
    if (!expiresAt || Date.now() > expiresAt - SKEW_MS) {
      const config = useRuntimeConfig()
      // refresh 失敗時はここでログアウト等はしない。
      // 一時的なネットワーク失敗での誤ログアウトを避けるため、セッション切れの最終判断は
      // 既存 interceptor（401 → refresh 失敗 → logout → /login）に委ねる。
      await performTokenRefresh(config, authStore)
    }
  }
})
