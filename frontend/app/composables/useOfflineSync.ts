import { offlineDb, type OfflineQueueItem } from '~/composables/useOfflineDb'

/**
 * F11.1 PWA: オフライン同期 composable。
 *
 * IndexedDB (Dexie) のオフラインキューを管理し、オンライン復帰時に
 * バッチで API に送信する。Background Sync API 対応ブラウザでは
 * SW にも同期を委譲する。
 */

function nowIso(): string {
  return new Date().toISOString().replace('Z', '').slice(0, 19)
}

function generateClientId(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 11)}`
}

export function useOfflineSync() {
  const online = useOnline()
  const syncStore = useSyncStore()

  /**
   * オフラインキューにアイテムを追加する。
   */
  async function enqueue(
    item: Omit<OfflineQueueItem, 'id' | 'clientId' | 'status' | 'retryCount' | 'errorMessage' | 'createdAt' | 'syncedAt'>,
  ): Promise<OfflineQueueItem> {
    const record: OfflineQueueItem = {
      clientId: generateClientId(),
      method: item.method,
      path: item.path,
      body: item.body,
      version: item.version,
      status: 'PENDING',
      retryCount: 0,
      errorMessage: null,
      createdAt: nowIso(),
      syncedAt: null,
    }
    const id = await offlineDb.offlineQueue.add(record)
    const created = { ...record, id: id as number }
    await updatePendingCount()
    await registerBackgroundSync()
    return created
  }

  /**
   * PENDING + FAILED のアイテムを createdAt 昇順で取得し、
   * 50 件ずつバッチで POST /api/v1/sync に送信する。
   */
  async function syncAll(): Promise<{ success: number; failed: number; conflicts: number }> {
    if (syncStore.syncInProgress) return { success: 0, failed: 0, conflicts: 0 }

    syncStore.startSync()
    try {
      const pending = await offlineDb.offlineQueue
        .where('status')
        .anyOf(['PENDING', 'FAILED'])
        .sortBy('createdAt')

      if (pending.length === 0) {
        syncStore.finishSync()
        await updatePendingCount()
        return { success: 0, failed: 0, conflicts: 0 }
      }

      const batch = pending.slice(0, 50)
      const result = await syncBatch(batch)

      syncStore.finishSync()
      await updatePendingCount()
      return result
    } catch {
      syncStore.finishSync()
      await updatePendingCount()
      return { success: 0, failed: 0, conflicts: 0 }
    }
  }

  /**
   * 1 バッチ分の送信処理。結果に応じてキュー内の status を更新する。
   */
  async function syncBatch(
    items: OfflineQueueItem[],
  ): Promise<{ success: number; failed: number; conflicts: number }> {
    const payload = items.map((item) => ({
      client_id: item.clientId,
      method: item.method,
      path: item.path,
      body: item.body,
      created_at: item.createdAt,
      version: item.version,
    }))

    try {
      const api = useApi()
      const response = await api<{
        data: {
          results: Array<{
            client_id: string
            status: 'SUCCESS' | 'CONFLICT' | 'FAILED'
            message?: string
          }>
        }
      }>('/api/v1/sync', {
        method: 'POST',
        body: { items: payload },
      })

      let success = 0
      let failed = 0
      let conflicts = 0

      for (const r of response.data?.results || []) {
        const item = items.find((p) => p.clientId === r.client_id)
        if (!item?.id) continue

        if (r.status === 'SUCCESS') {
          await offlineDb.offlineQueue.delete(item.id)
          success++
        } else if (r.status === 'CONFLICT') {
          await offlineDb.offlineQueue.update(item.id, { status: 'CONFLICT' as const })
          syncStore.addConflict({
            clientId: item.clientId,
            path: item.path,
            message: r.message ?? '',
          })
          conflicts++
        } else {
          const retryCount = (item.retryCount || 0) + 1
          await offlineDb.offlineQueue.update(item.id, {
            status: (retryCount >= 3 ? 'FAILED' : 'PENDING') as OfflineQueueItem['status'],
            retryCount,
            errorMessage: r.message ?? null,
          })
          failed++
        }
      }

      return { success, failed, conflicts }
    } catch {
      // ネットワークエラー時は全件をリトライ可能な状態に留める
      return { success: 0, failed: items.length, conflicts: 0 }
    }
  }

  /**
   * Background Sync API を登録する。非対応ブラウザではスキップ。
   */
  async function registerBackgroundSync(): Promise<void> {
    if (typeof navigator === 'undefined') return
    if (!('serviceWorker' in navigator)) return

    try {
      const reg = await navigator.serviceWorker.ready
      // SyncManager の存在チェック（型定義がない場合があるため）
      if ('sync' in reg) {
        await (reg as ServiceWorkerRegistration & { sync: { register: (tag: string) => Promise<void> } }).sync.register('mannschaft-offline-sync')
      }
    } catch {
      // Background Sync 未対応ブラウザは無視
    }
  }

  /**
   * 未送信件数を useSyncStore に反映する。
   */
  async function updatePendingCount(): Promise<void> {
    try {
      const count = await offlineDb.offlineQueue
        .where('status')
        .anyOf(['PENDING', 'FAILED'])
        .count()
      syncStore.updatePendingCount(count)
    } catch {
      // DB アクセス失敗時はカウント更新をスキップ
    }
  }

  /**
   * 未送信件数を返す。
   */
  async function getPendingCount(): Promise<number> {
    try {
      return await offlineDb.offlineQueue
        .where('status')
        .anyOf(['PENDING', 'FAILED'])
        .count()
    } catch {
      return 0
    }
  }

  // オンライン復帰時に自動同期
  watch(online, (isOnline) => {
    if (isOnline) {
      syncAll()
    }
  })

  return {
    enqueue,
    syncAll,
    syncBatch,
    registerBackgroundSync,
    updatePendingCount,
    getPendingCount,
  }
}
