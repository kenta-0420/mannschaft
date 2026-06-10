/**
 * F08.10 入口①: 大会対戦表ページ（fixtures.vue）のグルーピング／記録主体突合の純粋ロジック。
 * UI 非依存の純関数として切り出し、ユニットテストで挙動を直接検証する。
 */
import type { TournamentMatch } from '~/types/tournament'
import type { HomeAway } from '~/types/match'

/** participantId → 参加チーム情報（チーム名／数値 teamId）。 */
export interface ParticipantInfo {
  teamId: number
  displayName: string
}

/** 会場・日付でまとめた fixture グループ。 */
export interface FixtureGroup {
  /** グループキー（会場+日付）。 */
  key: string
  venue: string | null
  /** グループ代表日時（最初の fixture の予定日時・ISO 文字列）。 */
  date: string | null
  fixtures: TournamentMatch[]
}

/** 記録主体の決定結果（home/away participant.teamId と自チーム集合の突合結果）。 */
export interface RecordTarget {
  selfParticipantId: number
  selfTeamId: number
  homeAway: HomeAway
  opponentParticipantId: number | undefined
}

/**
 * fixture 群を「会場+日付（日単位）」でまとめる。
 * 会場/日付が無い fixture は「未定」グループとして末尾に回す。
 * 日付つきグループは日付昇順、未定は最後。
 */
export function groupFixtures(fixtures: TournamentMatch[]): FixtureGroup[] {
  const groups = new Map<string, FixtureGroup>()
  for (const fx of fixtures) {
    const venue = fx.info?.venue?.trim() ? fx.info.venue : null
    const date = fx.info?.scheduledDatetime ?? null
    // 日付は「日」単位でまとめる（時刻違いは同一グループに集約）。
    const dayKey = date ? date.slice(0, 10) : null
    const key = `${venue ?? '__novenue__'}__${dayKey ?? '__nodate__'}`
    const existing = groups.get(key)
    if (existing) {
      existing.fixtures.push(fx)
    } else {
      groups.set(key, { key, venue, date, fixtures: [fx] })
    }
  }
  return [...groups.values()].sort((g1, g2) => {
    const u1 = g1.venue == null && g1.date == null ? 1 : 0
    const u2 = g2.venue == null && g2.date == null ? 1 : 0
    if (u1 !== u2) return u1 - u2
    return (g1.date ?? '').localeCompare(g2.date ?? '')
  })
}

/**
 * home/away participant.teamId と自チーム集合を突合し、記録主体を決める。
 * home 一致を優先する（05 §H.1.2 home participant=HOME 固定）。一致ゼロなら null。
 */
export function resolveRecordTarget(
  fx: TournamentMatch,
  pMap: Map<number, ParticipantInfo>,
  ownTeamIds: Set<number>,
): RecordTarget | null {
  const homePid = fx.participants?.homeParticipantId
  const awayPid = fx.participants?.awayParticipantId
  const homeTeamId = homePid != null ? pMap.get(homePid)?.teamId : undefined
  const awayTeamId = awayPid != null ? pMap.get(awayPid)?.teamId : undefined

  if (homeTeamId != null && ownTeamIds.has(homeTeamId)) {
    return {
      selfParticipantId: homePid as number,
      selfTeamId: homeTeamId,
      homeAway: 'HOME',
      opponentParticipantId: awayPid,
    }
  }
  if (awayTeamId != null && ownTeamIds.has(awayTeamId)) {
    return {
      selfParticipantId: awayPid as number,
      selfTeamId: awayTeamId,
      homeAway: 'AWAY',
      opponentParticipantId: homePid,
    }
  }
  return null
}
