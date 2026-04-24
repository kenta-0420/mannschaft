/**
 * F13.1 Phase 13.1.2 QR チェックインのオフラインキュー自動 flush プラグイン。
 *
 * <p>{@code window} の {@code online} イベントで {@link useJobCheckInApi#flushQueue} を叩く。
 * 認証状態は flushQueue 内部の API 呼び出しに任せる（未ログインなら 401 で break し
 * 次回 flush に持ち越されるだけ）。</p>
 *
 * <p>副作用が最小限になるよう、キューが空なら即 return するガードを噛ませる。</p>
 */

export default defineNuxtPlugin(() => {
  if (typeof window === 'undefined') return

  let running = false
  const tryFlush = async () => {
    if (running) return
    running = true
    try {
      const api = useJobCheckInApi()
      const pending = await api.pendingCount()
      if (pending <= 0) return
      await api.flushQueue()
    }
    catch {
      // flush 失敗は致命的ではない。次回 online イベントでリトライされる。
    }
    finally {
      running = false
    }
  }

  window.addEventListener('online', () => {
    void tryFlush()
  })

  // 起動時にもキューが残っていれば一度 flush を試みる（ブラウザが前回オフライン中に閉じられたケース）。
  if (navigator.onLine) {
    void tryFlush()
  }
})
