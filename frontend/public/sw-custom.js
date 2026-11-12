/* eslint-disable no-restricted-globals */
// @ts-nocheck
/**
 * F11.1 / F04.3 Service Worker カスタムロジック（Web Push の表示と通知タップ）。
 *
 * @vite-pwa/nuxt（generateSW）が生成する sw.js へ、nuxt.config.ts の `pwa.workbox.importScripts`
 * で取り込まれる。ここには push / notificationclick だけを置く。
 * Background Sync の処理は未結線のまま public/sw-background-sync.js へ退避している（別台帳）。
 *
 * ペイロードの形は BE の WebPushPayload（設計書 F04.3「Push ペイロード」）:
 *   { title, body, data: { notification_id, action_url, priority } }
 * 見本: backend/src/test/resources/contracts/web-push-payload.json
 */

/** title が無い・読めないときの既定の題（ブランド名のため翻訳しない）。 */
const DEFAULT_PUSH_TITLE = 'Mannschaft'

/** URL 解析で読み飛ばされ、"/\t/evil" を "//evil" に化けさせる制御文字・空白と、"\" を拒否する。 */
// eslint-disable-next-line no-control-regex
const UNSAFE_URL_CHARS = /[\u0000- \u007f\\]/

/**
 * push の data を読み、表示に使う形へ正規化する。空・JSON でない・title 欠落でも例外を投げない。
 */
function parsePushPayload(pushData) {
  let raw = null
  if (pushData) {
    try {
      raw = pushData.json()
    } catch (e) {
      console.warn('[sw] push payload が JSON ではないため既定の題で表示する', e)
      raw = null
    }
  }
  const payload = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {}
  const title = typeof payload.title === 'string' && payload.title !== '' ? payload.title : DEFAULT_PUSH_TITLE
  const body = typeof payload.body === 'string' ? payload.body : ''
  const data = payload.data && typeof payload.data === 'object' && !Array.isArray(payload.data) ? payload.data : {}
  return { title, body, data }
}

/**
 * action_url を遷移先の絶対 URL に解決する。
 * 「/ 始まりの相対パス」か「この SW と同じオリジンの絶対 URL」だけを通し、それ以外は origin + '/'。
 */
function resolveTargetUrl(actionUrl) {
  const origin = self.location.origin
  const fallback = `${origin}/`
  if (typeof actionUrl !== 'string' || actionUrl === '' || UNSAFE_URL_CHARS.test(actionUrl)) {
    return fallback
  }
  if (actionUrl.startsWith('//')) return fallback
  let url
  try {
    url = actionUrl.startsWith('/') ? new URL(actionUrl, origin) : new URL(actionUrl)
  } catch {
    return fallback
  }
  return url.origin === origin ? url.href : fallback
}

function isSameOrigin(clientUrl) {
  try {
    return new URL(clientUrl).origin === self.location.origin
  } catch {
    return false
  }
}

/**
 * 同一オリジンのタブがあれば前面に出して遷移させ、無ければ（または遷移に失敗したら）新しいタブで開く。
 * 返す Promise は navigate / openWindow が終わるまで解決しない（waitUntil で SW の寿命を保つため）。
 */
async function openOrFocus(targetUrl) {
  let windowClients = []
  try {
    windowClients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
  } catch (e) {
    console.warn('[sw] clients.matchAll に失敗したため新しいタブで開く', e)
    windowClients = []
  }
  const tab = windowClients.find((c) => isSameOrigin(c.url))
  if (tab) {
    // focus は通知クリックのユーザー操作の猶予内に呼ぶ必要があるため navigate より先に呼ぶ
    try {
      await tab.focus()
    } catch (e) {
      console.warn('[sw] WindowClient.focus に失敗したが遷移は続ける', e)
    }
    try {
      await tab.navigate(targetUrl)
      return
    } catch (e) {
      // SW の制御下に無いタブでは navigate が TypeError で拒否される
      console.warn('[sw] WindowClient.navigate に失敗したため新しいタブで開く', e)
    }
  }
  await self.clients.openWindow(targetUrl)
}

// === Push 通知受信（F04.3 連携） ===
self.addEventListener('push', (event) => {
  const { title, body, data } = parsePushPayload(event.data)
  const options = {
    body,
    icon: '/icons/icon-192x192.png',
    badge: '/icons/badge-72x72.png',
    data,
    vibrate: [100, 50, 100],
    actions: [
      { action: 'open', title: '開く' },
      { action: 'dismiss', title: '閉じる' },
    ],
  }
  event.waitUntil(self.registration.showNotification(title, options))
})

// === 通知タップ時のディープリンク ===
self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  if (event.action === 'dismiss') return
  const targetUrl = resolveTargetUrl(event.notification.data?.action_url)
  event.waitUntil(openOrFocus(targetUrl))
})
