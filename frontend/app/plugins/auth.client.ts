export default defineNuxtPlugin(() => {
  const authStore = useAuthStore()
  authStore.loadFromStorage()

  if (authStore.isAuthenticated) {
    // 先回り（proactive）リフレッシュタイマーを武装する。
    // tokenExpiresAt が失効済み・期限不明（0）の場合は armProactiveRefresh 内部で
    // computeProactiveRefreshDelayMs が遅延0（即時）を返すため、従来の「失効済みなら即時更新」
    // 挙動もこの一本化されたスケジューラでカバーされる。以後もセッションが続く限り
    // 失効の約60秒前に自動で再武装され続ける（通知/chat/mentions/inbox 等の背景ポーラーが
    // 401 ノイズを出す前にトークンを常に新鮮に保つ）。
    //
    // ★白画面根治の要★ armProactiveRefresh 自体は同期関数で、内部で
    //   performTokenRefresh の Promise を await しない（void 化済み）ため、
    //   async プラグインの app mount を一切ブロックしない。
    const config = useRuntimeConfig()
    armProactiveRefresh(config, authStore)
  }
})
