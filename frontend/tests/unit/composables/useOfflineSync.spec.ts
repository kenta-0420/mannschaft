import { describe, it, expect, beforeEach } from 'vitest'
import { offlineDb } from '~/composables/useOfflineDb'
import type { OfflineQueueItem } from '~/composables/useOfflineDb'

/**
 * F11.1 useOfflineSync のユニットテスト。
 *
 * Dexie (offlineDb) の直接操作で enqueue / getPendingCount を検証する。
 * syncAll / syncBatch は API (useApi) に依存するため、
 * ここでは Dexie レイヤーの動作のみをテストする。
 */

function nowIso(): string {
  return new Date().toISOString().replace('Z', '').slice(0, 19)
}

function generateClientId(): string {
  return `test-${Date.now()}-${Math.random().toString(36).slice(2, 11)}`
}

beforeEach(async () => {
  await offlineDb.offlineQueue.clear()
})

describe('useOfflineSync (Dexie 操作)', () => {
  it('offlineQueue に PENDING アイテムを追加できる', async () => {
    const item: Omit<OfflineQueueItem, 'id'> = {
      clientId: generateClientId(),
      method: 'POST',
      path: '/api/v1/action-memos',
      body: { content: 'テスト' },
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
    expect(retrieved?.status).toBe('PENDING')
    expect(retrieved?.method).toBe('POST')
  })

  it('PENDING + FAILED アイテムの件数を取得できる', async () => {
    await offlineDb.offlineQueue.bulkAdd([
      {
        clientId: generateClientId(),
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
        clientId: generateClientId(),
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
        clientId: generateClientId(),
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

    const pendingCount = await offlineDb.offlineQueue
      .where('status')
      .anyOf(['PENDING', 'FAILED'])
      .count()

    expect(pendingCount).toBe(2)
  })

  it('createdAt 昇順でソートして取得できる', async () => {
    await offlineDb.offlineQueue.add({
      clientId: 'c-second',
      method: 'POST',
      path: '/api/v1/test',
      body: { order: 2 },
      version: null,
      status: 'PENDING',
      retryCount: 0,
      errorMessage: null,
      createdAt: '2026-04-10T10:00:01',
      syncedAt: null,
    })
    await offlineDb.offlineQueue.add({
      clientId: 'c-first',
      method: 'POST',
      path: '/api/v1/test',
      body: { order: 1 },
      version: null,
      status: 'PENDING',
      retryCount: 0,
      errorMessage: null,
      createdAt: '2026-04-10T10:00:00',
      syncedAt: null,
    })

    const sorted = await offlineDb.offlineQueue
      .where('status')
      .anyOf(['PENDING', 'FAILED'])
      .sortBy('createdAt')

    expect(sorted[0]?.clientId).toBe('c-first')
    expect(sorted[1]?.clientId).toBe('c-second')
  })

  it('成功アイテムを削除できる', async () => {
    const id = await offlineDb.offlineQueue.add({
      clientId: generateClientId(),
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

    await offlineDb.offlineQueue.delete(id)
    const count = await offlineDb.offlineQueue.count()
    expect(count).toBe(0)
  })

  it('status を CONFLICT に更新できる', async () => {
    const id = await offlineDb.offlineQueue.add({
      clientId: generateClientId(),
      method: 'POST',
      path: '/api/v1/test',
      body: {},
      version: 1,
      status: 'PENDING',
      retryCount: 0,
      errorMessage: null,
      createdAt: nowIso(),
      syncedAt: null,
    })

    await offlineDb.offlineQueue.update(id, { status: 'CONFLICT' })
    const retrieved = await offlineDb.offlineQueue.get(id)
    expect(retrieved?.status).toBe('CONFLICT')
  })

  it('retryCount を増やして FAILED に遷移できる', async () => {
    const id = await offlineDb.offlineQueue.add({
      clientId: generateClientId(),
      method: 'POST',
      path: '/api/v1/test',
      body: {},
      version: null,
      status: 'PENDING',
      retryCount: 2,
      errorMessage: null,
      createdAt: nowIso(),
      syncedAt: null,
    })

    const retryCount = 3
    await offlineDb.offlineQueue.update(id, {
      status: retryCount >= 3 ? 'FAILED' : 'PENDING',
      retryCount,
      errorMessage: 'Server error',
    })

    const retrieved = await offlineDb.offlineQueue.get(id)
    expect(retrieved?.status).toBe('FAILED')
    expect(retrieved?.retryCount).toBe(3)
    expect(retrieved?.errorMessage).toBe('Server error')
  })

  it('50件バッチのスライスが正しく動作する', async () => {
    // 60件のアイテムを追加
    const items = Array.from({ length: 60 }, (_, i) => ({
      clientId: `batch-${i}`,
      method: 'POST' as const,
      path: '/api/v1/test',
      body: { index: i },
      version: null,
      status: 'PENDING' as const,
      retryCount: 0,
      errorMessage: null,
      createdAt: `2026-04-10T10:00:${String(i).padStart(2, '0')}`,
      syncedAt: null,
    }))
    await offlineDb.offlineQueue.bulkAdd(items)

    const pending = await offlineDb.offlineQueue
      .where('status')
      .anyOf(['PENDING', 'FAILED'])
      .sortBy('createdAt')

    const batch = pending.slice(0, 50)
    expect(batch.length).toBe(50)
    expect(pending.length).toBe(60)
  })
})
