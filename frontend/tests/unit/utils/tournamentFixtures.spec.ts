import { describe, it, expect } from 'vitest'
import {
  groupFixtures,
  resolveRecordTarget,
  type ParticipantInfo,
} from '~/utils/tournamentFixtures'
import type { TournamentMatch } from '~/types/tournament'

/**
 * F08.10 入口①: 大会対戦表のグルーピング／記録主体突合ロジックのユニットテスト。
 *
 *  FX-GROUP-001: 同一会場・同一日付の fixture は 1 グループに集約される
 *  FX-GROUP-002: 別会場 or 別日付は別グループに分かれる
 *  FX-GROUP-003: 時刻違い・同一日付は同一グループに集約される（日単位グルーピング）
 *  FX-GROUP-004: 会場/日付が無い fixture は末尾「未定」グループに回る
 *  FX-GROUP-005: 日付つきグループは日付昇順に並ぶ
 *  FX-MATCH-001: home participant.teamId が自チームなら HOME で記録主体になる
 *  FX-MATCH-002: away participant.teamId が自チームなら AWAY で記録主体になる
 *  FX-MATCH-003: home/away 両方一致なら home 優先（HOME）
 *  FX-MATCH-004: 一致なしなら null
 */

function fx(overrides: Partial<TournamentMatch> & { id: number }): TournamentMatch {
  return {
    id: overrides.id,
    participants: overrides.participants,
    score: overrides.score,
    info: overrides.info,
  }
}

describe('groupFixtures', () => {
  it('FX-GROUP-001: 同一会場・同一日付は 1 グループに集約される', () => {
    const groups = groupFixtures([
      fx({ id: 1, info: { venue: '市民球場', scheduledDatetime: '2026-06-10T10:00:00' } }),
      fx({ id: 2, info: { venue: '市民球場', scheduledDatetime: '2026-06-10T13:00:00' } }),
    ])
    expect(groups).toHaveLength(1)
    expect(groups[0]!.venue).toBe('市民球場')
    expect(groups[0]!.fixtures.map((f) => f.id)).toEqual([1, 2])
  })

  it('FX-GROUP-002: 別会場は別グループ', () => {
    const groups = groupFixtures([
      fx({ id: 1, info: { venue: 'A球場', scheduledDatetime: '2026-06-10T10:00:00' } }),
      fx({ id: 2, info: { venue: 'B球場', scheduledDatetime: '2026-06-10T10:00:00' } }),
    ])
    expect(groups).toHaveLength(2)
  })

  it('FX-GROUP-003: 時刻違い・同一日付・同一会場は同一グループ（日単位）', () => {
    const groups = groupFixtures([
      fx({ id: 1, info: { venue: 'A球場', scheduledDatetime: '2026-06-10T09:00:00' } }),
      fx({ id: 2, info: { venue: 'A球場', scheduledDatetime: '2026-06-10T17:30:00' } }),
    ])
    expect(groups).toHaveLength(1)
    expect(groups[0]!.fixtures).toHaveLength(2)
  })

  it('FX-GROUP-004: 会場/日付なしは末尾「未定」グループに回る', () => {
    const groups = groupFixtures([
      fx({ id: 1, info: {} }),
      fx({ id: 2, info: { venue: 'A球場', scheduledDatetime: '2026-06-10T10:00:00' } }),
    ])
    expect(groups).toHaveLength(2)
    // 未定グループ（venue/date とも null）が最後
    const last = groups[groups.length - 1]!
    expect(last.venue).toBeNull()
    expect(last.date).toBeNull()
    expect(last.fixtures.map((f) => f.id)).toEqual([1])
  })

  it('FX-GROUP-005: 日付つきグループは日付昇順に並ぶ', () => {
    const groups = groupFixtures([
      fx({ id: 1, info: { venue: 'A', scheduledDatetime: '2026-06-12T10:00:00' } }),
      fx({ id: 2, info: { venue: 'B', scheduledDatetime: '2026-06-10T10:00:00' } }),
    ])
    expect(groups.map((g) => g.fixtures[0]!.id)).toEqual([2, 1])
  })
})

describe('resolveRecordTarget', () => {
  const pMap = new Map<number, ParticipantInfo>([
    [100, { teamId: 11, displayName: 'ホームFC' }],
    [200, { teamId: 22, displayName: 'アウェイSC' }],
  ])
  const fixture = fx({
    id: 9,
    participants: { homeParticipantId: 100, awayParticipantId: 200 },
  })

  it('FX-MATCH-001: home が自チームなら HOME で記録主体', () => {
    const t = resolveRecordTarget(fixture, pMap, new Set([11]))
    expect(t).toEqual({
      selfParticipantId: 100,
      selfTeamId: 11,
      homeAway: 'HOME',
      opponentParticipantId: 200,
    })
  })

  it('FX-MATCH-002: away が自チームなら AWAY で記録主体', () => {
    const t = resolveRecordTarget(fixture, pMap, new Set([22]))
    expect(t).toEqual({
      selfParticipantId: 200,
      selfTeamId: 22,
      homeAway: 'AWAY',
      opponentParticipantId: 100,
    })
  })

  it('FX-MATCH-003: home/away 両方一致なら home 優先（HOME）', () => {
    const t = resolveRecordTarget(fixture, pMap, new Set([11, 22]))
    expect(t?.homeAway).toBe('HOME')
    expect(t?.selfTeamId).toBe(11)
  })

  it('FX-MATCH-004: 一致なしなら null', () => {
    const t = resolveRecordTarget(fixture, pMap, new Set([99]))
    expect(t).toBeNull()
  })

  it('FX-MATCH-005: participant 未定（id null）なら null', () => {
    const unscheduled = fx({ id: 10, participants: {} })
    expect(resolveRecordTarget(unscheduled, pMap, new Set([11]))).toBeNull()
  })
})
