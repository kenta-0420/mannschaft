import { describe, it, expect, beforeEach, vi } from 'vitest'
import {
  useMatchOfflineQueue,
  buildMatchEventClientId,
  parsePath,
  __resetMatchDexieAvailabilityForTest,
  type QueuedMatchEvent,
} from '~/composables/match/useMatchOfflineQueue'
import type { MatchEventRequest, MatchEventResponse } from '~/types/match'

/**
 * F08.10 useMatchOfflineQueue ユニットテスト（オフライン退避＋再送・§G.11）。
 * fake-indexeddb 上で Dexie が in-memory 動作する想定。
 *
 * 観点:
 *   OFF-001: enqueue → getPending で取り出せる
 *   OFF-002: 同一 clientId の二重 enqueue は 1 件のまま（重複排除）
 *   OFF-003: flushAll 成功で送信されキューから消える
 *   OFF-004: flushAll は 1 件失敗でそこで break する（残りは保持）
 *   OFF-005: buildMatchEventClientId / parsePath のヘルパ
 */

const ORG = 7
const MATCH = 'm-uuid-1'

function sampleEvent(overrides: Partial<QueuedMatchEvent> = {}): QueuedMatchEvent {
  return {
    orgId: ORG,
    matchId: MATCH,
    clientId: 'c-1',
    body: { eventType: 'GOAL', period: 'FIRST_HALF', teamSide: 'HOME', minute: 12 } as MatchEventRequest,
    ...overrides,
  }
}

beforeEach(async () => {
  __resetMatchDexieAvailabilityForTest()
  const q = useMatchOfflineQueue()
  await q.clearAll()
})

describe('ヘルパ', () => {
  it('OFF-005: buildMatchEventClientId は名前空間プレフィックスを付ける', () => {
    expect(buildMatchEventClientId('abc')).toBe('match-event:abc')
  })

  it('OFF-005: parsePath は orgId/matchId を抽出する', () => {
    expect(parsePath(`/api/v1/organizations/${ORG}/matches/${MATCH}/events`)).toEqual({
      orgId: ORG,
      matchId: MATCH,
    })
    expect(parsePath('/api/v1/teams/1/members')).toEqual({ orgId: null, matchId: null })
  })
})

describe('useMatchOfflineQueue', () => {
  it('OFF-001: enqueue → getPending で取り出せる', async () => {
    const q = useMatchOfflineQueue()
    await q.enqueue(sampleEvent())
    const pending = await q.getPending()
    expect(pending.length).toBe(1)
    expect(pending[0]?.clientId).toBe('match-event:c-1')
    expect(pending[0]?.path).toBe(`/api/v1/organizations/${ORG}/matches/${MATCH}/events`)
  })

  it('OFF-002: 同一 clientId の二重 enqueue は重複排除される', async () => {
    const q = useMatchOfflineQueue()
    const id1 = await q.enqueue(sampleEvent({ clientId: 'dup' }))
    const id2 = await q.enqueue(sampleEvent({ clientId: 'dup' }))
    expect(id1).toBe(id2)
    expect(await q.count()).toBe(1)
  })

  it('OFF-003: flushAll 成功でキューから消える', async () => {
    const q = useMatchOfflineQueue()
    await q.enqueue(sampleEvent({ clientId: 'a' }))
    await q.enqueue(sampleEvent({ clientId: 'b' }))
    const sender = vi.fn(async (_o: number, _m: string, body: MatchEventRequest) =>
      ({ ...body, id: 'sent' }) as MatchEventResponse,
    )
    const results = await q.flushAll(sender)
    expect(results.length).toBe(2)
    expect(results.every((r) => r.response)).toBe(true)
    expect(sender).toHaveBeenCalledTimes(2)
    expect(sender).toHaveBeenCalledWith(ORG, MATCH, expect.objectContaining({ eventType: 'GOAL' }))
    expect(await q.count()).toBe(0)
  })

  it('OFF-004: flushAll は 1 件失敗でそこで break し残りを保持する', async () => {
    const q = useMatchOfflineQueue()
    await q.enqueue(sampleEvent({ clientId: 'a' }))
    await q.enqueue(sampleEvent({ clientId: 'b' }))
    let n = 0
    const sender = vi.fn(async (_o: number, _m: string, body: MatchEventRequest) => {
      n += 1
      if (n === 1) throw new Error('network down')
      return { ...body, id: 'sent' } as MatchEventResponse
    })
    const results = await q.flushAll(sender)
    // 1 件目で失敗 → break。送信は 1 回のみ、キューには 2 件残る。
    expect(results.length).toBe(1)
    expect(results[0]?.error).toBeInstanceOf(Error)
    expect(sender).toHaveBeenCalledTimes(1)
    expect(await q.count()).toBe(2)
  })
})
