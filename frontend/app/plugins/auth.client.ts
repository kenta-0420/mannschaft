export default defineNuxtPlugin(() => {
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
      // 先回り更新は「最初の認証付き API を撃つ前に新トークンを用意する」最適化に過ぎない。
      // 失敗しても useApi の 401 interceptor が回収する（refresh → 自動リトライ）ため await しない。
      //
      // ★白画面根治の要★ ここで await すると、refresh API がハングした際に
      //   async プラグインの await が永久 pending になり Nuxt の app mount をブロックし、
      //   layouts/default.vue の LoadingBounce フォールバックが固着して白画面化する。
      // fire-and-forget（void）にすることで mount を一切ブロックしない。
      // refresh 失敗時もここでログアウト等はしない（誤ログアウト回避。最終判断は interceptor に委ねる）。
      void performTokenRefresh(config, authStore)
    }
  }
})
