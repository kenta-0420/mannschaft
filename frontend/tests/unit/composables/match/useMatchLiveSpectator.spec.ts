import { describe, it, expect } from 'vitest'
import {
  applyLiveUpdate,
  emptySnapshot,
  liveEventViewToResponse,
  liveTopicDestination,
  parseLivePayload,
  upsertEvent,
  type SpectatorSnapshot,
} from '~/composables/match/useMatchLiveSpectator'
import type {
  MatchEventResponse,
  MatchLiveEventView,
  MatchLiveUpdatePayload,
} from '~/types/match'

/**
 * F08.10 ライブ観戦の差分適用ロジック ユニットテスト（07_realtime_spectator.md §J.4）。
 *
 * 観点:
 *   SPEC-001: 連続 seq（lastSeq+1）の EVENT_ADDED は APPLIED でイベントが追加され lastSeq 更新
 *   SPEC-002: 古い/重複 seq（<=lastSeq）は IGNORED でスナップショット不変
 *   SPEC-003: 飛んだ seq（>lastSeq+1）は RESYNC（適用せずスナップショット再取得を要求）
 *   SPEC-004: EVENT_UPDATED は同一 id を置換（重複させない・sortSeq 昇順維持）
 *   SPEC-005: EVENT_DELETED は eventId のイベントを除去
 *   SPEC-006: SCORE_UPDATED はスコア/PK を反映（NULL は据え置き）
 *   SPEC-007: STATUS_CHANGED はステータスを反映
 *   SPEC-008: applyLiveUpdate は純関数（入力スナップショットを破壊しない）
 *   SPEC-009: upsertEvent は sortSeq 昇順で upsert（同一 id 置換）
 *   SPEC-010: liveEventViewToResponse は配信ビュー→レスポンス形へ変換（機微 ID は欠落）
 *   SPEC-011: parseLivePayload は不正フレーム（非 JSON / serverSeq 欠落）を null にする
 *   SPEC-012: liveTopicDestination は BE 配信先と一致
 *   SPEC-013: 初回適用（lastSeq=0）は seq=1 を連続とみなす／seq=2 は RESYNC
 */

function evView(id: string, sortSeq: number, over: Partial<MatchLiveEventView> = {}): MatchLiveEventView {
  return { id, eventType: 'GOAL', teamSide: 'HOME', sortSeq, ...over }
}

function evRes(id: string, sortSeq: number): MatchEventResponse {
  return { id, eventType: 'GOAL', teamSide: 'HOME', sortSeq }
}

function payload(over: Partial<MatchLiveUpdatePayload> & { serverSeq: number; type: MatchLiveUpdatePayload['type'] }): MatchLiveUpdatePayload {
  return over
}

describe('applyLiveUpdate — serverSeq の順序保証・重複排除・飛び検知', () => {
  it('SPEC-001: 連続 seq の EVENT_ADDED は APPLIED でイベント追加・lastSeq 更新', () => {
    const snap = { ...emptySnapshot(), lastSeq: 5 }
    const { result, snapshot } = applyLiveUpdate(
      snap,
      payload({ type: 'EVENT_ADDED', serverSeq: 6, event: evView('e1', 1) }),
    )
    expect(result).toBe('APPLIED')
    expect(snapshot.lastSeq).toBe(6)
    expect(snapshot.events.map((e) => e.id)).toEqual(['e1'])
  })

  it('SPEC-002: 古い/重複 seq（<=lastSeq）は IGNORED でスナップショット不変', () => {
    const snap: SpectatorSnapshot = { ...emptySnapshot(), lastSeq: 5, events: [evRes('e1', 1)] }
    const equal = applyLiveUpdate(snap, payload({ type: 'EVENT_ADDED', serverSeq: 5, event: evView('e2', 2) }))
    expect(equal.result).toBe('IGNORED')
    expect(equal.snapshot).toBe(snap)

    const older = applyLiveUpdate(snap, payload({ type: 'EVENT_ADDED', serverSeq: 3, event: evView('e3', 3) }))
    expect(older.result).toBe('IGNORED')
    expect(older.snapshot).toBe(snap)
  })

  it('SPEC-003: 飛んだ seq（>lastSeq+1）は RESYNC で適用せずスナップショット不変', () => {
    const snap: SpectatorSnapshot = { ...emptySnapshot(), lastSeq: 5, events: [evRes('e1', 1)] }
    const { result, snapshot } = applyLiveUpdate(
      snap,
      payload({ type: 'EVENT_ADDED', serverSeq: 8, event: evView('e9', 9) }),
    )
    expect(result).toBe('RESYNC')
    expect(snapshot).toBe(snap)
    expect(snapshot.events.map((e) => e.id)).toEqual(['e1'])
  })

  it('SPEC-004: EVENT_UPDATED は同一 id を置換（重複させず sortSeq 昇順維持）', () => {
    const snap: SpectatorSnapshot = {
      ...emptySnapshot(),
      lastSeq: 1,
      events: [evRes('e1', 1), evRes('e2', 2)],
    }
    const { result, snapshot } = applyLiveUpdate(
      snap,
      payload({ type: 'EVENT_UPDATED', serverSeq: 2, event: evView('e1', 3, { note: 'fixed' }) }),
    )
    expect(result).toBe('APPLIED')
    // e1 は置換され sortSeq=3 へ、再ソートで e2(2) → e1(3)
    expect(snapshot.events.map((e) => e.id)).toEqual(['e2', 'e1'])
    expect(snapshot.events.find((e) => e.id === 'e1')?.note).toBe('fixed')
    expect(snapshot.events.filter((e) => e.id === 'e1')).toHaveLength(1)
  })

  it('SPEC-005: EVENT_DELETED は eventId のイベントを除去', () => {
    const snap: SpectatorSnapshot = {
      ...emptySnapshot(),
      lastSeq: 1,
      events: [evRes('e1', 1), evRes('e2', 2)],
    }
    const { result, snapshot } = applyLiveUpdate(
      snap,
      payload({ type: 'EVENT_DELETED', serverSeq: 2, eventId: 'e1' }),
    )
    expect(result).toBe('APPLIED')
    expect(snapshot.events.map((e) => e.id)).toEqual(['e2'])
  })

  it('SPEC-006: SCORE_UPDATED はスコア/PK を反映（NULL は据え置き）', () => {
    const snap: SpectatorSnapshot = {
      ...emptySnapshot(),
      lastSeq: 1,
      homeScore: 1,
      awayScore: 1,
      homePenaltyScore: 0,
      awayPenaltyScore: 0,
    }
    const { snapshot } = applyLiveUpdate(
      snap,
      payload({
        type: 'SCORE_UPDATED',
        serverSeq: 2,
        score: { homeScore: 2, awayScore: 1, homePenaltyScore: null, awayPenaltyScore: null },
      }),
    )
    expect(snapshot.homeScore).toBe(2)
    expect(snapshot.awayScore).toBe(1)
    // NULL のフィールドは据え置き（0 のまま）
    expect(snapshot.homePenaltyScore).toBe(0)
    expect(snapshot.awayPenaltyScore).toBe(0)
  })

  it('SPEC-007: STATUS_CHANGED はステータスを反映', () => {
    const snap = { ...emptySnapshot(), lastSeq: 1, status: 'IN_PROGRESS' as const }
    const { snapshot } = applyLiveUpdate(
      snap,
      payload({ type: 'STATUS_CHANGED', serverSeq: 2, status: 'COMPLETED' }),
    )
    expect(snapshot.status).toBe('COMPLETED')
  })

  it('SPEC-008: applyLiveUpdate は純関数（入力スナップショットを破壊しない）', () => {
    const snap: SpectatorSnapshot = { ...emptySnapshot(), lastSeq: 1, events: [evRes('e1', 1)] }
    const before = JSON.stringify(snap)
    applyLiveUpdate(snap, payload({ type: 'EVENT_ADDED', serverSeq: 2, event: evView('e2', 2) }))
    expect(JSON.stringify(snap)).toBe(before)
  })

  it('SPEC-013: 初回適用（lastSeq=0）は seq=1 を連続とみなし seq=2 は RESYNC', () => {
    const first = applyLiveUpdate(
      emptySnapshot(),
      payload({ type: 'EVENT_ADDED', serverSeq: 1, event: evView('e1', 1) }),
    )
    expect(first.result).toBe('APPLIED')
    expect(first.snapshot.lastSeq).toBe(1)

    const jumped = applyLiveUpdate(
      emptySnapshot(),
      payload({ type: 'EVENT_ADDED', serverSeq: 2, event: evView('e1', 1) }),
    )
    expect(jumped.result).toBe('RESYNC')
  })
})

describe('upsertEvent / liveEventViewToResponse / parseLivePayload / liveTopicDestination', () => {
  it('SPEC-009: upsertEvent は sortSeq 昇順で upsert（同一 id 置換・新配列）', () => {
    const events = [evRes('a', 2), evRes('b', 4)]
    const out = upsertEvent(events, evRes('c', 3))
    expect(out.map((e) => e.id)).toEqual(['a', 'c', 'b'])
    expect(out).not.toBe(events)

    const replaced = upsertEvent(out, { id: 'a', eventType: 'ASSIST', teamSide: 'HOME', sortSeq: 5 })
    expect(replaced.filter((e) => e.id === 'a')).toHaveLength(1)
    expect(replaced.map((e) => e.id)).toEqual(['c', 'b', 'a'])
  })

  it('SPEC-010: liveEventViewToResponse は配信ビュー→レスポンス形へ変換（機微 ID は欠落）', () => {
    const res = liveEventViewToResponse(
      evView('e1', 7, { minute: 12, playerName: '10番', relatedPlayerName: '7番', note: 'n' }),
    )
    expect(res.id).toBe('e1')
    expect(res.sortSeq).toBe(7)
    expect(res.minute).toBe(12)
    expect(res.playerName).toBe('10番')
    expect(res.relatedPlayerName).toBe('7番')
    // 配信ビューは内部 ID を持たないため欠落する（read-only ＝編集に不要）。
    expect(res).not.toHaveProperty('playerUserId')
  })

  it('SPEC-011: parseLivePayload は不正フレームを null にする', () => {
    expect(parseLivePayload('not-json')).toBeNull()
    expect(parseLivePayload('null')).toBeNull()
    expect(parseLivePayload(JSON.stringify({ type: 'EVENT_ADDED' }))).toBeNull() // serverSeq 欠落
    expect(parseLivePayload(JSON.stringify({ serverSeq: 1 }))).toBeNull() // type 欠落
    const ok = parseLivePayload(JSON.stringify({ type: 'STATUS_CHANGED', serverSeq: 3, status: 'COMPLETED' }))
    expect(ok).not.toBeNull()
    expect(ok?.serverSeq).toBe(3)
    expect(ok?.type).toBe('STATUS_CHANGED')
  })

  it('SPEC-012: liveTopicDestination は BE 配信先と一致', () => {
    expect(liveTopicDestination('m-uuid-1')).toBe('/topic/matches/m-uuid-1/live')
  })
})
