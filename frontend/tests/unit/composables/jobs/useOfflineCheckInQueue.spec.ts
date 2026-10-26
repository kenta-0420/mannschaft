import { describe, it, expect, beforeEach, vi } from 'vitest'
import {
  useOfflineCheckInQueue,
  buildClientId,
  __resetDexieAvailabilityForTest,
  type QueuedCheckInPayload,
} from '~/composables/jobs/useOfflineCheckInQueue'
import type { CheckInResponse } from '~/types/jobmatching'

/**
 * F13.1 Phase 13.1.2 useOfflineCheckInQueue のユニットテスト。
 *
 * <p>fake-indexeddb により Dexie が in-memory で動作する想定。</p>
 */

function samplePayload(overrides: Partial<QueuedCheckInPayload> = {}): QueuedCheckInPayload {
  return {
    contractId: 4001,
    token: 'jwt-A',
    shortCode: null,
    type: 'IN',
    scannedAt: '2026-04-24T12:00:00.000Z',
    offlineSubmitted: false,
    manualCodeFallback: false,
    geoLat: 35.6,
    geoLng: 139.7,
    geoAccuracy: 10,
    clientUserAgent: 'ua/1.0',
    ...overrides,
  }
}

beforeEach(async () => {
  __resetDexieAvailabilityForTest()
  const queue = useOfflineCheckInQueue()
  await queue.clearAll()
})

describe('buildClientId', () => {
  it('token 経路なら token 文字列を含む clientId を生成する', () => {
    expect(buildClientId(samplePayload({ token: 'xyz' }))).toBe('jobs-check-in:token:xyz')
  })

  it('shortCode 経路なら contractId + type + shortCode を含む clientId を生成する', () => {
    const id = buildClientId(samplePayload({ token: null, shortCode: 'ABC123' }))
    expect(id).toBe('jobs-check-in:short:4001:IN:ABC123')
  })

  it('どちらも無ければ anon + ランダムサフィックスになる', () => {
    const id = buildClientId(samplePayload({ token: null, shortCode: null }))
    expect(id.startsWith('jobs-check-in:anon:4001:IN:')).toBe(true)
  })
})

describe('useOfflineCheckInQueue', () => {
  it('enqueue → getPending で取り出せる', async () => {
    const queue = useOfflineCheckInQueue()
    await queue.enqueue(samplePayload())
    const pending = await queue.getPending()
    expect(pending.length).toBe(1)
    expect(pending[0]?.path).toBe('/api/v1/jobs/check-ins')
    expect((pending[0]?.body as Record<string, unknown>).token).toBe('jwt-A')
  })

  it('同一 token の重複 enqueue は新規キュー項目を追加しない', async () => {
    const queue = useOfflineCheckInQueue()
    const first = await queue.enqueue(samplePayload({ token: 'dup' }))
    const second = await queue.enqueue(samplePayload({ token: 'dup' }))
    expect(first).toBe(second)
    const pending = await queue.getPending()
    expect(pending.length).toBe(1)
  })

  it('token 違いなら別項目として積まれる', async () => {
    const queue = useOfflineCheckInQueue()
    await queue.enqueue(samplePayload({ token: 'a' }))
    await queue.enqueue(samplePayload({ token: 'b' }))
    expect((await queue.getPending()).length).toBe(2)
  })

  it('count はキュー件数を返す', async () => {
    const queue = useOfflineCheckInQueue()
    expect(await queue.count()).toBe(0)
    await queue.enqueue(samplePayload({ token: 'a' }))
    expect(await queue.count()).toBe(1)
  })

  it('flushAll が順次 sender を呼び、成功項目はキューから消える', async () => {
    const queue = useOfflineCheckInQueue()
    await queue.enqueue(samplePayload({ token: 'a' }))
    await queue.enqueue(samplePayload({ token: 'b' }))

    const sender = vi.fn(async (p: QueuedCheckInPayload): Promise<CheckInResponse> => ({
      checkInId: 1,
      contractId: p.contractId,
      type: p.type,
      newStatus: 'IN_PROGRESS',
      workDurationMinutes: null,
      geoAnomaly: false,
    }))
    const results = await queue.flushAll(sender)
    expect(results.length).toBe(2)
    expect(sender).toHaveBeenCalledTimes(2)
    expect(await queue.count()).toBe(0)
  })

  it('flushAll の途中で失敗したら break し、残りは次回に持ち越される', async () => {
    const queue = useOfflineCheckInQueue()
    await queue.enqueue(samplePayload({ token: 'a' }))
    await queue.enqueue(samplePayload({ token: 'b' }))

    let callCount = 0
    const sender = vi.fn(async (p: QueuedCheckInPayload): Promise<CheckInResponse> => {
      callCount++
      if (callCount === 1) {
        return {
          checkInId: 1,
          contractId: p.contractId,
          type: p.type,
          newStatus: 'IN_PROGRESS',
          workDurationMinutes: null,
          geoAnomaly: false,
        }
      }
      throw new Error('network down')
    })
    const results = await queue.flushAll(sender)
    expect(results.length).toBe(2)
    expect(results[1]?.error).toBeInstanceOf(Error)
    // 2 件目が残っているはず
    expect(await queue.count()).toBe(1)
  })

  it('flushAll が sender に offlineSubmitted=true を伝える', async () => {
    const queue = useOfflineCheckInQueue()
    await queue.enqueue(samplePayload({ token: 'a', offlineSubmitted: false }))

    const sender = vi.fn(async (p: QueuedCheckInPayload): Promise<CheckInResponse> => {
      expect(p.offlineSubmitted).toBe(true)
      return {
        checkInId: 9,
        contractId: p.contractId,
        type: p.type,
        newStatus: 'IN_PROGRESS',
        workDurationMinutes: null,
        geoAnomaly: false,
      }
    })
    await queue.flushAll(sender)
    expect(sender).toHaveBeenCalledOnce()
  })
})
