/**
 * F11.1 PWA: Service Worker からの JWT トークン取得リクエストに応答するプラグイン。
 *
 * Background Sync 時に SW が API 呼び出しに JWT を付与する必要があるが、
 * SW コンテキストから localStorage にはアクセスできない。
 * そのため、SW からの MessageChannel 経由のリクエストに応答する。
 */
export default defineNuxtPlugin(() => {
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return

  navigator.serviceWorker.addEventListener('message', (event) => {
    if (event.data?.type === 'GET_AUTH_TOKEN') {
      const token = localStorage.getItem('accessToken')
      event.ports[0]?.postMessage({ token })
    }
  })
})
