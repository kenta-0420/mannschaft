/* eslint-disable no-restricted-globals */
// @ts-nocheck
/**
 * F11.1 Service Worker カスタムロジック。
 *
 * @vite-pwa/nuxt が生成する SW に加え、Push 通知・通知タップ・Background Sync を処理する。
 * SW 内では Vue composable は使用不可のため、Dexie をスタンドアロンで利用する。
 */

// === Push 通知受信（F04.3 連携） ===
self.addEventListener('push', (event) => {
  const payload = event.data?.json()
  if (!payload) return
  const options = {
    body: payload.body,
    icon: '/icons/icon-192x192.png',
    badge: '/icons/badge-72x72.png',
    tag: payload.tag,
    data: payload.data,
    vibrate: [100, 50, 100],
    actions: [
      { action: 'open', title: '開く' },
      { action: 'dismiss', title: '閉じる' },
    ],
  }
  event.waitUntil(self.registration.showNotification(payload.title, options))
})

// === 通知タップ時のディープリンク ===
self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  if (event.action === 'dismiss') return
  const actionUrl = event.notification.data?.action_url || '/'
  event.waitUntil(
    self.clients
      .matchAll({ type: 'window', includeUncontrolled: true })
      .then((windowClients) => {
        for (const client of windowClients) {
          if (client.url.includes(self.location.origin)) {
            client.navigate(actionUrl)
            return client.focus()
          }
        }
        return self.clients.openWindow(actionUrl)
      }),
  )
})

// === Background Sync: オフラインキューの送信 ===
self.addEventListener('sync', (event) => {
  if (event.tag === 'mannschaft-offline-sync') {
    event.waitUntil(syncOfflineQueue())
  }
})

/**
 * SW 内でオフラインキューを同期する。
 *
 * Dexie は SW 内でも動作するが、バンドル外なので IndexedDB を直接操作する。
 * JWT トークンは localStorage から直接読めない（SW コンテキスト）ため、
 * クライアントにメッセージを送って同期を委譲するフォールバックも含む。
 */
async function syncOfflineQueue() {
  try {
    const db = await openOfflineDb()
    const tx = db.transaction('offlineQueue', 'readonly')
    const store = tx.objectStore('offlineQueue')
    const statusIndex = store.index('status')

    const pending = await getAllFromIndex(statusIndex, 'PENDING')
    const failed = await getAllFromIndex(statusIndex, 'FAILED')
    const items = [...pending, ...failed]
      .sort((a, b) => (a.createdAt < b.createdAt ? -1 : 1))
      .slice(0, 50)

    if (items.length === 0) {
      db.close()
      return
    }

    const syncPayload = items.map((item) => ({
      client_id: item.clientId,
      method: item.method,
      path: item.path,
      body: item.body,
      created_at: item.createdAt,
      version: item.version,
    }))

    // JWT をクライアントから取得する試み
    const token = await getTokenFromClient()

    const headers = { 'Content-Type': 'application/json' }
    if (token) {
      headers['Authorization'] = `Bearer ${token}`
    }

    const response = await fetch('/api/v1/sync', {
      method: 'POST',
      headers,
      body: JSON.stringify({ items: syncPayload }),
    })

    if (!response.ok) {
      db.close()
      return
    }

    const result = await response.json()
    const writeTx = db.transaction('offlineQueue', 'readwrite')
    const writeStore = writeTx.objectStore('offlineQueue')

    for (const r of result.data?.results || []) {
      const item = items.find((p) => p.clientId === r.client_id)
      if (!item || !item.id) continue

      if (r.status === 'SUCCESS') {
        writeStore.delete(item.id)
      } else if (r.status === 'CONFLICT') {
        const updateReq = writeStore.get(item.id)
        updateReq.onsuccess = () => {
          const record = updateReq.result
          if (record) {
            record.status = 'CONFLICT'
            writeStore.put(record)
          }
        }
      } else {
        const updateReq = writeStore.get(item.id)
        updateReq.onsuccess = () => {
          const record = updateReq.result
          if (record) {
            record.retryCount = (record.retryCount || 0) + 1
            record.status = record.retryCount >= 3 ? 'FAILED' : 'PENDING'
            record.errorMessage = r.message || null
            writeStore.put(record)
          }
        }
      }
    }

    await new Promise((resolve, reject) => {
      writeTx.oncomplete = resolve
      writeTx.onerror = reject
    })
    db.close()
  } catch {
    // Background Sync は自動リトライするので、エラーは握りつぶさず再スローしない
    // （次の sync イベントで再試行される）
  }
}

/**
 * IndexedDB を直接開く（SW 内では Dexie バンドルが使えないため）。
 */
function openOfflineDb() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('mannschaft-offline', 1)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains('offlineQueue')) {
        const store = db.createObjectStore('offlineQueue', {
          keyPath: 'id',
          autoIncrement: true,
        })
        store.createIndex('clientId', 'clientId', { unique: false })
        store.createIndex('status', 'status', { unique: false })
        store.createIndex('createdAt', 'createdAt', { unique: false })
      }
      if (!db.objectStoreNames.contains('cachedResponses')) {
        const store = db.createObjectStore('cachedResponses', {
          keyPath: 'id',
          autoIncrement: true,
        })
        store.createIndex('url', 'url', { unique: false })
        store.createIndex('expiresAt', 'expiresAt', { unique: false })
      }
      if (!db.objectStoreNames.contains('offlineDrafts')) {
        const store = db.createObjectStore('offlineDrafts', {
          keyPath: 'id',
          autoIncrement: true,
        })
        store.createIndex('draftType', 'draftType', { unique: false })
        store.createIndex('scopeType_scopeId', ['scopeType', 'scopeId'], { unique: false })
        store.createIndex('updatedAt', 'updatedAt', { unique: false })
      }
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

/**
 * IDBIndex から特定キーの全レコードを取得するヘルパー。
 */
function getAllFromIndex(index, key) {
  return new Promise((resolve) => {
    const req = index.getAll(key)
    req.onsuccess = () => resolve(req.result || [])
    req.onerror = () => resolve([])
  })
}

/**
 * クライアント（ウィンドウ）にメッセージを送り、JWT トークンを受け取る。
 * タイムアウト（2秒）で諦める。
 */
function getTokenFromClient() {
  return new Promise((resolve) => {
    const timeout = setTimeout(() => resolve(null), 2000)
    self.clients
      .matchAll({ type: 'window', includeUncontrolled: true })
      .then((clients) => {
        if (clients.length === 0) {
          clearTimeout(timeout)
          resolve(null)
          return
        }
        const channel = new MessageChannel()
        channel.port1.onmessage = (event) => {
          clearTimeout(timeout)
          resolve(event.data?.token || null)
        }
        clients[0].postMessage({ type: 'GET_AUTH_TOKEN' }, [channel.port2])
      })
      .catch(() => {
        clearTimeout(timeout)
        resolve(null)
      })
  })
}
