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

  // ─── 追加テスト +15件 ──────────────────────────────────────────────────────

  it('status: SUCCESS のアイテムは PENDING/FAILED カウントに含まれない', async () => {
    await offlineDb.offlineQueue.bulkAdd([
      {
        clientId: generateClientId(),
        method: 'POST' as const,
        path: '/api/v1/test',
        body: {},
        version: null,
        status: 'SUCCESS' as const,
        retryCount: 0,
        errorMessage: null,
        createdAt: nowIso(),
        syncedAt: nowIso(),
      },
      {
        clientId: generateClientId(),
        method: 'POST' as const,
        path: '/api/v1/test',
        body: {},
        version: null,
        status: 'SUCCESS' as const,
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

    expect(pendingCount).toBe(0)
  })

  it('status: CONFLICT のアイテムは PENDING/FAILED カウントに含まれない', async () => {
    await offlineDb.offlineQueue.bulkAdd([
      {
        clientId: generateClientId(),
        method: 'PATCH' as const,
        path: '/api/v1/test',
        body: {},
        version: 1,
        status: 'CONFLICT' as const,
        retryCount: 0,
        errorMessage: null,
        createdAt: nowIso(),
        syncedAt: null,
      },
      {
        clientId: generateClientId(),
        method: 'POST' as const,
        path: '/api/v1/test',
        body: {},
        version: null,
        status: 'PENDING' as const,
        retryCount: 0,
        errorMessage: null,
        createdAt: nowIso(),
        syncedAt: null,
      },
    ])

    const conflictCount = await offlineDb.offlineQueue
      .where('status')
      .equals('CONFLICT')
      .count()

    expect(conflictCount).toBe(1)

    const pendingCount = await offlineDb.offlineQueue
      .where('status')
      .anyOf(['PENDING', 'FAILED'])
      .count()

    // CONFLICT は PENDING/FAILED に含まれない
    expect(pendingCount).toBe(1)
  })

  it('DELETE メソッドのアイテムを追加・取得できる', async () => {
    const id = await offlineDb.offlineQueue.add({
      clientId: generateClientId(),
      method: 'DELETE' as const,
      path: '/api/v1/test/123',
      body: {},
      version: null,
      status: 'PENDING' as const,
      retryCount: 0,
      errorMessage: null,
      createdAt: nowIso(),
      syncedAt: null,
    })

    const item = await offlineDb.offlineQueue.get(id)
    expect(item?.method).toBe('DELETE')
    expect(item?.path).toBe('/api/v1/test/123')
  })

  it('PATCH メソッドのアイテムに version を付与して追加できる', async () => {
    const id = await offlineDb.offlineQueue.add({
      clientId: generateClientId(),
      method: 'PATCH' as const,
      path: '/api/v1/test/456',
      body: { title: '更新タイトル' },
      version: 5,
      status: 'PENDING' as const,
      retryCount: 0,
      errorMessage: null,
      createdAt: nowIso(),
      syncedAt: null,
    })

    const item = await offlineDb.offlineQueue.get(id)
    expect(item?.method).toBe('PATCH')
    expect(item?.version).toBe(5)
    expect(item?.body).toEqual({ title: '更新タイトル' })
  })

  it('clientId で特定のアイテムを取得できる', async () => {
    const uniqueClientId = `unique-client-${Date.now()}`
    await offlineDb.offlineQueue.add({
      clientId: uniqueClientId,
      method: 'POST' as const,
      path: '/api/v1/test',
      body: { data: 'test' },
      version: null,
      status: 'PENDING' as const,
      retryCount: 0,
      errorMessage: null,
      createdAt: nowIso(),
      syncedAt: null,
    })

    const item = await offlineDb.offlineQueue
      .where('clientId')
      .equals(uniqueClientId)
      .first()

    expect(item).toBeDefined()
    expect(item?.clientId).toBe(uniqueClientId)
  })

  it('同期済み(SUCCESS)に更新すると syncedAt が設定される', async () => {
    const id = await offlineDb.offlineQueue.add({
      clientId: generateClientId(),
      method: 'POST' as const,
      path: '/api/v1/test',
      body: {},
      version: null,
      status: 'PENDING' as const,
      retryCount: 0,
      errorMessage: null,
      createdAt: nowIso(),
      syncedAt: null,
    })

    const syncedAt = nowIso()
    await offlineDb.offlineQueue.update(id, {
      status: 'SUCCESS' as const,
      syncedAt,
    })

    const item = await offlineDb.offlineQueue.get(id)
    expect(item?.status).toBe('SUCCESS')
    expect(item?.syncedAt).toBe(syncedAt)
  })

  it('複数の FAILED アイテムを一括取得できる', async () => {
    await offlineDb.offlineQueue.bulkAdd([
      {
        clientId: generateClientId(),
        method: 'POST' as const,
        path: '/api/v1/test',
        body: {},
        version: null,
        status: 'FAILED' as const,
        retryCount: 3,
        errorMessage: 'error 1',
        createdAt: '2026-04-10T09:00:00',
        syncedAt: null,
      },
      {
        clientId: generateClientId(),
        method: 'POST' as const,
        path: '/api/v1/test',
        body: {},
        version: null,
        status: 'FAILED' as const,
        retryCount: 3,
        errorMessage: 'error 2',
        createdAt: '2026-04-10T09:00:01',
        syncedAt: null,
      },
      {
        clientId: generateClientId(),
        method: 'POST' as const,
        path: '/api/v1/test',
        body: {},
        version: null,
        status: 'PENDING' as const,
        retryCount: 0,
        errorMessage: null,
        createdAt: '2026-04-10T09:00:02',
        syncedAt: null,
      },
    ])

    const failed = await offlineDb.offlineQueue
      .where('status')
      .equals('FAILED')
      .toArray()

    expect(failed.length).toBe(2)
    expect(failed.every((i) => i.errorMessage !== null)).toBe(true)
  })

  it('createdAt 降順で取得して最新アイテムが先頭になる', async () => {
    await offlineDb.offlineQueue.bulkAdd([
      {
        clientId: 'c-oldest',
        method: 'POST' as const,
        path: '/api/v1/test',
        body: {},
        version: null,
        status: 'PENDING' as const,
        retryCount: 0,
        errorMessage: null,
        createdAt: '2026-04-10T08:00:00',
        syncedAt: null,
      },
      {
        clientId: 'c-newest',
        method: 'POST' as const,
        path: '/api/v1/test',
        body: {},
        version: null,
        status: 'PENDING' as const,
        retryCount: 0,
        errorMessage: null,
        createdAt: '2026-04-10T12:00:00',
        syncedAt: null,
      },
      {
        clientId: 'c-middle',
        method: 'POST' as const,
        path: '/api/v1/test',
        body: {},
        version: null,
        status: 'PENDING' as const,
        retryCount: 0,
        errorMessage: null,
        createdAt: '2026-04-10T10:00:00',
        syncedAt: null,
      },
    ])

    const sorted = await offlineDb.offlineQueue
      .where('status')
      .anyOf(['PENDING', 'FAILED'])
      .sortBy('createdAt')

    const reversed = [...sorted].reverse()
    expect(reversed[0]?.clientId).toBe('c-newest')
    expect(reversed[2]?.clientId).toBe('c-oldest')
  })

  it('retryCount が 0→1→2 と段階的に増加できる', async () => {
    const id = await offlineDb.offlineQueue.add({
      clientId: generateClientId(),
      method: 'POST' as const,
      path: '/api/v1/test',
      body: {},
      version: null,
      status: 'PENDING' as const,
      retryCount: 0,
      errorMessage: null,
      createdAt: nowIso(),
      syncedAt: null,
    })

    for (let retry = 1; retry <= 2; retry++) {
      await offlineDb.offlineQueue.update(id, {
        retryCount: retry,
        errorMessage: `attempt ${retry} failed`,
      })
      const item = await offlineDb.offlineQueue.get(id)
      expect(item?.retryCount).toBe(retry)
    }

    const finalItem = await offlineDb.offlineQueue.get(id)
    expect(finalItem?.retryCount).toBe(2)
    expect(finalItem?.status).toBe('PENDING')
  })

  it('空キューでの PENDING/FAILED count は 0 を返す', async () => {
    // beforeEach で clear 済みなので追加なし
    const count = await offlineDb.offlineQueue
      .where('status')
      .anyOf(['PENDING', 'FAILED'])
      .count()

    expect(count).toBe(0)
  })

  it('bulkAdd で複数アイテムを一括追加できる', async () => {
    const items = Array.from({ length: 5 }, (_, i) => ({
      clientId: `bulk-${i}`,
      method: 'POST' as const,
      path: '/api/v1/test',
      body: { idx: i },
      version: null,
      status: 'PENDING' as const,
      retryCount: 0,
      errorMessage: null,
      createdAt: nowIso(),
      syncedAt: null,
    }))

    await offlineDb.offlineQueue.bulkAdd(items)

    const total = await offlineDb.offlineQueue.count()
    expect(total).toBe(5)
  })

  it('clear() でテーブルが空になる', async () => {
    await offlineDb.offlineQueue.bulkAdd([
      {
        clientId: generateClientId(),
        method: 'POST' as const,
        path: '/api/v1/test',
        body: {},
        version: null,
        status: 'PENDING' as const,
        retryCount: 0,
        errorMessage: null,
        createdAt: nowIso(),
        syncedAt: null,
      },
      {
        clientId: generateClientId(),
        method: 'POST' as const,
        path: '/api/v1/test',
        body: {},
        version: null,
        status: 'FAILED' as const,
        retryCount: 3,
        errorMessage: 'error',
        createdAt: nowIso(),
        syncedAt: null,
      },
    ])

    const beforeCount = await offlineDb.offlineQueue.count()
    expect(beforeCount).toBe(2)

    await offlineDb.offlineQueue.clear()

    const afterCount = await offlineDb.offlineQueue.count()
    expect(afterCount).toBe(0)
  })

  it('errorMessage が null のアイテムのみをフィルタできる', async () => {
    await offlineDb.offlineQueue.bulkAdd([
      {
        clientId: generateClientId(),
        method: 'POST' as const,
        path: '/api/v1/test',
        body: {},
        version: null,
        status: 'PENDING' as const,
        retryCount: 0,
        errorMessage: null,
        createdAt: nowIso(),
        syncedAt: null,
      },
      {
        clientId: generateClientId(),
        method: 'POST' as const,
        path: '/api/v1/test',
        body: {},
        version: null,
        status: 'FAILED' as const,
        retryCount: 3,
        errorMessage: 'something went wrong',
        createdAt: nowIso(),
        syncedAt: null,
      },
    ])

    const allItems = await offlineDb.offlineQueue.toArray()
    const withoutError = allItems.filter((i) => i.errorMessage === null)
    const withError = allItems.filter((i) => i.errorMessage !== null)

    expect(withoutError.length).toBe(1)
    expect(withError.length).toBe(1)
  })

  it('path が異なるアイテムを個別に取得できる', async () => {
    await offlineDb.offlineQueue.bulkAdd([
      {
        clientId: generateClientId(),
        method: 'POST' as const,
        path: '/api/v1/action-memos',
        body: {},
        version: null,
        status: 'PENDING' as const,
        retryCount: 0,
        errorMessage: null,
        createdAt: nowIso(),
        syncedAt: null,
      },
      {
        clientId: generateClientId(),
        method: 'POST' as const,
        path: '/api/v1/posts',
        body: {},
        version: null,
        status: 'PENDING' as const,
        retryCount: 0,
        errorMessage: null,
        createdAt: nowIso(),
        syncedAt: null,
      },
    ])

    const allPending = await offlineDb.offlineQueue
      .where('status')
      .equals('PENDING')
      .toArray()

    const memoItems = allPending.filter((i) => i.path === '/api/v1/action-memos')
    const postItems = allPending.filter((i) => i.path === '/api/v1/posts')

    expect(memoItems.length).toBe(1)
    expect(postItems.length).toBe(1)
  })

  it('アイテムを削除後にカウントが正しく減少する', async () => {
    const ids = await offlineDb.offlineQueue.bulkAdd(
      [
        {
          clientId: generateClientId(),
          method: 'POST' as const,
          path: '/api/v1/test',
          body: {},
          version: null,
          status: 'PENDING' as const,
          retryCount: 0,
          errorMessage: null,
          createdAt: nowIso(),
          syncedAt: null,
        },
        {
          clientId: generateClientId(),
          method: 'POST' as const,
          path: '/api/v1/test',
          body: {},
          version: null,
          status: 'PENDING' as const,
          retryCount: 0,
          errorMessage: null,
          createdAt: nowIso(),
          syncedAt: null,
        },
        {
          clientId: generateClientId(),
          method: 'POST' as const,
          path: '/api/v1/test',
          body: {},
          version: null,
          status: 'PENDING' as const,
          retryCount: 0,
          errorMessage: null,
          createdAt: nowIso(),
          syncedAt: null,
        },
      ],
      { allKeys: true },
    )

    expect(await offlineDb.offlineQueue.count()).toBe(3)

    await offlineDb.offlineQueue.delete(ids[0] as number)
    expect(await offlineDb.offlineQueue.count()).toBe(2)

    await offlineDb.offlineQueue.delete(ids[1] as number)
    expect(await offlineDb.offlineQueue.count()).toBe(1)
  })

  it('version null と version 数値を混在させて管理できる', async () => {
    await offlineDb.offlineQueue.bulkAdd([
      {
        clientId: generateClientId(),
        method: 'POST' as const,
        path: '/api/v1/test',
        body: {},
        version: null,
        status: 'PENDING' as const,
        retryCount: 0,
        errorMessage: null,
        createdAt: nowIso(),
        syncedAt: null,
      },
      {
        clientId: generateClientId(),
        method: 'PUT' as const,
        path: '/api/v1/test/1',
        body: {},
        version: 10,
        status: 'PENDING' as const,
        retryCount: 0,
        errorMessage: null,
        createdAt: nowIso(),
        syncedAt: null,
      },
    ])

    const allItems = await offlineDb.offlineQueue.toArray()
    const nullVersion = allItems.filter((i) => i.version === null)
    const withVersion = allItems.filter((i) => i.version !== null)

    expect(nullVersion.length).toBe(1)
    expect(withVersion.length).toBe(1)
    expect(withVersion[0]?.version).toBe(10)
  })
})
