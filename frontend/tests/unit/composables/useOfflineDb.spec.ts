import { describe, it, expect, beforeEach } from 'vitest'
import { offlineDb } from '~/composables/useOfflineDb'
import type { OfflineQueueItem, CachedApiResponse, OfflineDraft } from '~/composables/useOfflineDb'

/**
 * F11.1 useOfflineDb のユニットテスト。
 *
 * Dexie.js ラッパーの基本 CRUD 操作を検証する。
 * テスト環境では fake-indexeddb が自動的に使われるか、
 * Dexie のメモリフォールバックが機能する。
 */

function nowIso(): string {
  return new Date().toISOString().replace('Z', '').slice(0, 19)
}

beforeEach(async () => {
  // 各テスト前にテーブルをクリア
  try {
    await offlineDb.offlineQueue.clear()
    await offlineDb.cachedResponses.clear()
    await offlineDb.offlineDrafts.clear()
  } catch {
    // テスト環境で IndexedDB が使えない場合はスキップ
  }
})

describe('useOfflineDb', () => {
  describe('offlineQueue テーブル', () => {
    it('アイテムを追加して取得できる', async () => {
      const item: Omit<OfflineQueueItem, 'id'> = {
        clientId: 'test-client-1',
        method: 'POST',
        path: '/api/v1/action-memos',
        body: { content: 'テストメモ' },
        version: null,
        status: 'PENDING',
        retryCount: 0,
        errorMessage: null,
        createdAt: nowIso(),
        syncedAt: null,
      }

      const id = await offlineDb.offlineQueue.add(item)
      expect(id).toBeDefined()

      const retrieved = await offlineDb.offlineQueue.get(id)
      expect(retrieved).toBeDefined()
      expect(retrieved?.clientId).toBe('test-client-1')
      expect(retrieved?.method).toBe('POST')
      expect(retrieved?.status).toBe('PENDING')
    })

    it('status でフィルタできる', async () => {
      await offlineDb.offlineQueue.bulkAdd([
        {
          clientId: 'c1',
          method: 'POST',
          path: '/api/v1/test',
          body: {},
          version: null,
          status: 'PENDING',
          retryCount: 0,
          errorMessage: null,
          createdAt: nowIso(),
          syncedAt: null,
        },
        {
          clientId: 'c2',
          method: 'POST',
          path: '/api/v1/test',
          body: {},
          version: null,
          status: 'FAILED',
          retryCount: 3,
          errorMessage: 'error',
          createdAt: nowIso(),
          syncedAt: null,
        },
        {
          clientId: 'c3',
          method: 'POST',
          path: '/api/v1/test',
          body: {},
          version: null,
          status: 'SUCCESS',
          retryCount: 0,
          errorMessage: null,
          createdAt: nowIso(),
          syncedAt: nowIso(),
        },
      ])

      const pendingOrFailed = await offlineDb.offlineQueue
        .where('status')
        .anyOf(['PENDING', 'FAILED'])
        .toArray()
      expect(pendingOrFailed.length).toBe(2)
    })

    it('アイテムを削除できる', async () => {
      const id = await offlineDb.offlineQueue.add({
        clientId: 'delete-test',
        method: 'DELETE',
        path: '/api/v1/test/1',
        body: {},
        version: null,
        status: 'PENDING',
        retryCount: 0,
        errorMessage: null,
        createdAt: nowIso(),
        syncedAt: null,
      })

      await offlineDb.offlineQueue.delete(id)
      const retrieved = await offlineDb.offlineQueue.get(id)
      expect(retrieved).toBeUndefined()
    })

    it('アイテムを更新できる', async () => {
      const id = await offlineDb.offlineQueue.add({
        clientId: 'update-test',
        method: 'POST',
        path: '/api/v1/test',
        body: {},
        version: null,
        status: 'PENDING',
        retryCount: 0,
        errorMessage: null,
        createdAt: nowIso(),
        syncedAt: null,
      })

      await offlineDb.offlineQueue.update(id, {
        status: 'SYNCING',
        retryCount: 1,
      })

      const retrieved = await offlineDb.offlineQueue.get(id)
      expect(retrieved?.status).toBe('SYNCING')
      expect(retrieved?.retryCount).toBe(1)
    })
  })

  describe('cachedResponses テーブル', () => {
    it('キャッシュを追加・取得できる', async () => {
      const item: Omit<CachedApiResponse, 'id'> = {
        url: '/api/v1/teams',
        data: { teams: [{ id: 1, name: 'テスト' }] },
        cachedAt: nowIso(),
        expiresAt: '2099-12-31T23:59:59',
      }

      const id = await offlineDb.cachedResponses.add(item)
      const retrieved = await offlineDb.cachedResponses.get(id)
      expect(retrieved?.url).toBe('/api/v1/teams')
    })
  })

  describe('offlineDrafts テーブル', () => {
    it('下書きを追加・取得できる', async () => {
      const item: Omit<OfflineDraft, 'id'> = {
        draftType: 'ACTION_MEMO',
        scopeType: 'PERSONAL',
        scopeId: 1,
        data: { content: '下書きメモ' },
        createdAt: nowIso(),
        updatedAt: nowIso(),
      }

      const id = await offlineDb.offlineDrafts.add(item)
      const retrieved = await offlineDb.offlineDrafts.get(id)
      expect(retrieved?.draftType).toBe('ACTION_MEMO')
      expect(retrieved?.scopeType).toBe('PERSONAL')
    })

    it('複合インデックス [scopeType+scopeId] でクエリできる', async () => {
      await offlineDb.offlineDrafts.bulkAdd([
        {
          draftType: 'ACTION_MEMO',
          scopeType: 'TEAM',
          scopeId: 1,
          data: { content: 'チーム1' },
          createdAt: nowIso(),
          updatedAt: nowIso(),
        },
        {
          draftType: 'CHAT_MESSAGE',
          scopeType: 'TEAM',
          scopeId: 1,
          data: { content: 'チャット' },
          createdAt: nowIso(),
          updatedAt: nowIso(),
        },
        {
          draftType: 'ACTION_MEMO',
          scopeType: 'TEAM',
          scopeId: 2,
          data: { content: 'チーム2' },
          createdAt: nowIso(),
          updatedAt: nowIso(),
        },
      ])

      const team1Drafts = await offlineDb.offlineDrafts
        .where('[scopeType+scopeId]')
        .equals(['TEAM', 1])
        .toArray()
      expect(team1Drafts.length).toBe(2)
    })
  })
})
