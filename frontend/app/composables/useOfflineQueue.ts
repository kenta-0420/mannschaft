import type { CreateActionMemoPayload, OfflineQueuedMemo } from '~/types/actionMemo'

/**
 * F02.5 行動メモのオフラインキュー composable。
 *
 * <p>設計書 §4.x「オフライン対応」に基づき、{@code navigator.onLine === false} の状態で
 * 発行された {@code createMemo} のペイロードを IndexedDB に退避し、オンライン復帰時または
 * 手動同期ボタン操作時に順次送信する。</p>
 *
 * <p><b>Phase 2 の縮退運用</b>: 本プロジェクトは {@code @vite-pwa/nuxt} 等の Service Worker
 * 機構が未導入のため、バックグラウンド同期は行わず、{@code window.addEventListener('online')}
 * と手動同期ボタンで代替する。Service Worker 連携は将来の F11.1 PWA 実装時に追加する
 * 未解決事項として設計書 §12 に残る。</p>
 *
 * <p>IndexedDB の使用可否が環境に左右されるため、ストレージが使えない場合はインメモリ
 * フォールバックに切り替わる（タブを閉じるとキューが失われるが、オフライン書きかけを
 * 完全に失わせないよりは優先度が低い）。</p>
 */

const DB_NAME = 'mannschaft-action-memo'
const DB_VERSION = 1
const STORE_NAME = 'actionMemoQueue'

type DbRecord = Omit<OfflineQueuedMemo, 'queueId'>

/** インメモリフォールバック（IndexedDB が使えない環境用） */
interface InMemoryFallback {
  items: OfflineQueuedMemo[]
  nextId: number
}

const _inMemory: InMemoryFallback = { items: [], nextId: 1 }
let _dbAvailable: boolean | null = null

function hasIndexedDb(): boolean {
  if (_dbAvailable !== null) return _dbAvailable
  try {
    _dbAvailable = typeof indexedDB !== 'undefined' && indexedDB !== null
  } catch {
    _dbAvailable = false
  }
  return _dbAvailable
}

/**
 * IndexedDB への接続。初回のみ ObjectStore を作成する。
 * 失敗時は {@code null} を返し、呼び出し側はインメモリにフォールバックする。
 */
function openDb(): Promise<IDBDatabase | null> {
  if (!hasIndexedDb()) return Promise.resolve(null)
  return new Promise((resolve) => {
    try {
      const req = indexedDB.open(DB_NAME, DB_VERSION)
      req.onerror = () => {
        // エラー時はフォールバックを優先
        resolve(null)
      }
      req.onupgradeneeded = () => {
        const db = req.result
        if (!db.objectStoreNames.contains(STORE_NAME)) {
          db.createObjectStore(STORE_NAME, { keyPath: 'queueId', autoIncrement: true })
        }
      }
      req.onsuccess = () => resolve(req.result)
    } catch {
      resolve(null)
    }
  })
}

function nowIso(): string {
  return new Date().toISOString().replace('Z', '').slice(0, 19)
}

export function useOfflineQueue() {
  /**
   * キューにペイロードを積む。IDB 書き込みに失敗したらインメモリに退避する。
   */
  async function enqueue(
    payload: CreateActionMemoPayload,
    tempId: number,
  ): Promise<OfflineQueuedMemo> {
    const record: DbRecord = {
      tempId,
      payload,
      enqueuedAt: nowIso(),
    }

    const db = await openDb()
    if (db) {
      return new Promise<OfflineQueuedMemo>((resolve) => {
        const tx = db.transaction(STORE_NAME, 'readwrite')
        const store = tx.objectStore(STORE_NAME)
        const addReq = store.add(record)
        addReq.onsuccess = () => {
          const queueId = addReq.result as number
          resolve({ queueId, ...record })
        }
        addReq.onerror = () => {
          // フォールバック
          const fallback: OfflineQueuedMemo = {
            queueId: _inMemory.nextId++,
            ...record,
          }
          _inMemory.items.push(fallback)
          resolve(fallback)
        }
      })
    }

    // インメモリフォールバック
    const fallback: OfflineQueuedMemo = {
      queueId: _inMemory.nextId++,
      ...record,
    }
    _inMemory.items.push(fallback)
    return fallback
  }

  /**
   * キュー内の全項目を enqueuedAt 昇順で取得する。
   */
  async function getAll(): Promise<OfflineQueuedMemo[]> {
    const db = await openDb()
    if (db) {
      return new Promise<OfflineQueuedMemo[]>((resolve) => {
        const tx = db.transaction(STORE_NAME, 'readonly')
        const store = tx.objectStore(STORE_NAME)
        const req = store.getAll()
        req.onsuccess = () => {
          const items = (req.result as OfflineQueuedMemo[]) ?? []
          items.sort((a, b) => (a.enqueuedAt < b.enqueuedAt ? -1 : 1))
          resolve(items)
        }
        req.onerror = () => resolve([..._inMemory.items])
      })
    }
    return [..._inMemory.items].sort((a, b) => (a.enqueuedAt < b.enqueuedAt ? -1 : 1))
  }

  /**
   * 指定 ID の項目を削除する。
   */
  async function remove(queueId: number): Promise<void> {
    const db = await openDb()
    if (db) {
      await new Promise<void>((resolve) => {
        const tx = db.transaction(STORE_NAME, 'readwrite')
        tx.objectStore(STORE_NAME).delete(queueId)
        tx.oncomplete = () => resolve()
        tx.onerror = () => resolve()
      })
    }
    _inMemory.items = _inMemory.items.filter((i) => i.queueId !== queueId)
  }

  /**
   * キューが空かどうか（UI バナー表示判定用）。
   */
  async function hasQueuedItems(): Promise<boolean> {
    const items = await getAll()
    return items.length > 0
  }

  /**
   * キュー件数（UI 表示用）。
   */
  async function count(): Promise<number> {
    const items = await getAll()
    return items.length
  }

  /**
   * キュー内の項目を順次送信する。
   *
   * <p>1 件ずつ try しながら送信し、成功した項目だけキューから消す。途中で失敗した
   * 項目は次回の flush で再試行される（429 レートリミットなどは即エラーとしてループを
   * 抜けることで Retry-After を尊重する）。</p>
   *
   * @param sender 送信関数。{@link useActionMemoApi#createMemo} を渡す想定
   * @returns 成功した項目の {@link OfflineQueuedMemo}（tempId を含む）配列
   */
  async function flushQueue(
    sender: (
      payload: CreateActionMemoPayload,
    ) => Promise<{ id: number } | null>,
  ): Promise<Array<{ queued: OfflineQueuedMemo; createdId: number }>> {
    const items = await getAll()
    const results: Array<{ queued: OfflineQueuedMemo; createdId: number }> = []
    for (const item of items) {
      try {
        const created = await sender(item.payload)
        if (created && typeof created.id === 'number') {
          results.push({ queued: item, createdId: created.id })
          if (typeof item.queueId === 'number') {
            await remove(item.queueId)
          }
        } else {
          // null = 送信失敗（store レイヤーが握りつぶしたケース）。次回に持ち越し
          break
        }
      } catch {
        // 429 / ネットワーク切断などは次回リトライに回す
        break
      }
    }
    return results
  }

  /**
   * テスト用: 全キューを削除する。本番コードからは通常呼ばない。
   */
  async function clearAll(): Promise<void> {
    const db = await openDb()
    if (db) {
      await new Promise<void>((resolve) => {
        const tx = db.transaction(STORE_NAME, 'readwrite')
        tx.objectStore(STORE_NAME).clear()
        tx.oncomplete = () => resolve()
        tx.onerror = () => resolve()
      })
    }
    _inMemory.items = []
    _inMemory.nextId = 1
  }

  return {
    enqueue,
    getAll,
    remove,
    hasQueuedItems,
    count,
    flushQueue,
    clearAll,
  }
}
