import Dexie, { type Table } from 'dexie'

/**
 * F11.1 PWA: Dexie.js ベースの IndexedDB ラッパー。
 *
 * 仕様書 5.4 に基づき、オフラインキュー・API キャッシュ・下書きの 3 テーブルを管理する。
 * SW 内からは直接 IndexedDB を操作するが、クライアント側ではこの Dexie インスタンスを使用する。
 */

export interface OfflineQueueItem {
  id?: number
  clientId: string
  method: 'POST' | 'PATCH' | 'PUT' | 'DELETE'
  path: string
  body: Record<string, unknown>
  version: number | null
  status: 'PENDING' | 'SYNCING' | 'SUCCESS' | 'FAILED' | 'CONFLICT'
  retryCount: number
  errorMessage: string | null
  createdAt: string
  syncedAt: string | null
}

export interface CachedApiResponse {
  id?: number
  url: string
  data: Record<string, unknown>
  cachedAt: string
  expiresAt: string
}

export interface OfflineDraft {
  id?: number
  draftType: 'ACTIVITY_RECORD' | 'CHAT_MESSAGE' | 'ATTENDANCE_RESPONSE' | 'ACTION_MEMO'
  scopeType: 'TEAM' | 'ORGANIZATION' | 'PERSONAL'
  scopeId: number
  data: Record<string, unknown>
  createdAt: string
  updatedAt: string
}

class MannschaftOfflineDb extends Dexie {
  offlineQueue!: Table<OfflineQueueItem>
  cachedResponses!: Table<CachedApiResponse>
  offlineDrafts!: Table<OfflineDraft>

  constructor() {
    super('mannschaft-offline')
    this.version(1).stores({
      offlineQueue: '++id, clientId, status, createdAt',
      cachedResponses: '++id, url, expiresAt',
      offlineDrafts: '++id, draftType, [scopeType+scopeId], updatedAt',
    })
  }
}

export const offlineDb = new MannschaftOfflineDb()
