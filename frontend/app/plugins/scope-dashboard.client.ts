/**
 * F22.1 横スワイプ・スコープダッシュボード — クライアントプラグイン
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/03_security_ux.md §2.5
 * 手本: frontend/app/plugins/nav-settings.client.ts（同パターン）
 *
 * 起動直後に localStorage から即時復元（チラつき防止）し、
 * 認証済みの場合はバックグラウンドでサーバー同期を行う。
 */
export default defineNuxtPlugin(async () => {
  const store = useScopeDashboardStore()
  const authStore = useAuthStore()

  // 同期・即時（localStorage → store）
  store.loadFromStorage()

  if (authStore.isAuthenticated) {
    // バックグラウンドでサーバー同期（チームタグを優先）
    await store.loadTabs('TEAM', store.teamTabPage)
  }
})
