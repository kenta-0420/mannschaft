import { describe, it, expect } from 'vitest'
import {
  buildParticipantNameMap,
  resolveParticipantName,
  buildScoreEntryRows,
  parseScoreInput,
  isRowValid,
  buildBatchScorePayload,
  deriveScoreEntryColumnFlags,
  extractStatus,
  isConflictError,
  isForbiddenError,
  buildCsvTemplate,
  SCORE_CSV_HEADER,
  type ScoreEntryRow,
} from '~/utils/tournamentScoreEntry'
import type { TournamentMatch, TournamentMatrix } from '~/types/tournament'

/**
 * F08.7 順位UI Wave2: スコア入力グリッド／CSV取込の純関数ユニットテスト。
 *
 * 重点:
 *  - バッチペイロードに楽観ロック version が必ず同梱されること
 *  - 両方未入力の行はスキップ・片方のみ入力は不正として保存中断（null）
 *  - フラグ有効時のみ延長/PK を同梱する（Wave B-2）
 *  - 409 衝突・403/404 権限のステータス判定
 *  - CSV テンプレ生成（matchId 入りひな形）
 */

/** 延長/PK 欄を含む完全な ScoreEntryRow を組み立てるヘルパ（欠けた欄は空文字）。 */
function makeRow(over: Partial<ScoreEntryRow> & { matchId: number }): ScoreEntryRow {
  return {
    matchId: over.matchId,
    version: over.version ?? 0,
    homeName: over.homeName ?? 'A',
    awayName: over.awayName ?? 'B',
    homeScore: over.homeScore ?? '',
    awayScore: over.awayScore ?? '',
    homeExtraScore: over.homeExtraScore ?? '',
    awayExtraScore: over.awayExtraScore ?? '',
    homePenaltyScore: over.homePenaltyScore ?? '',
    awayPenaltyScore: over.awayPenaltyScore ?? '',
  }
}

function fx(overrides: Partial<TournamentMatch> & { id: number }): TournamentMatch {
  return {
    id: overrides.id,
    participants: overrides.participants,
    score: overrides.score,
    audit: overrides.audit,
  }
}

const matrix: TournamentMatrix = {
  participants: [
    { participantId: 10, teamId: 100, teamName: 'Alpha' },
    { participantId: 20, teamId: 200, teamName: 'Bravo' },
  ],
  cells: {},
}

describe('buildParticipantNameMap / resolveParticipantName', () => {
  it('participantId → teamName のマップを作る', () => {
    const m = buildParticipantNameMap(matrix)
    expect(m.get(10)).toBe('Alpha')
    expect(m.get(20)).toBe('Bravo')
  })

  it('未解決の participantId は #id、id 無しは "-"', () => {
    const m = buildParticipantNameMap(matrix)
    expect(resolveParticipantName(999, m)).toBe('#999')
    expect(resolveParticipantName(undefined, m)).toBe('-')
  })

  it('matrix が null でも空マップを返す', () => {
    expect(buildParticipantNameMap(null).size).toBe(0)
  })
})

describe('buildScoreEntryRows', () => {
  it('version を audit.version から採用し、既存スコア（延長/PK含む）を入力欄初期値にする', () => {
    const m = buildParticipantNameMap(matrix)
    const matches = [
      fx({
        id: 1,
        participants: { homeParticipantId: 10, awayParticipantId: 20 },
        score: {
          homeScore: 2,
          awayScore: 1,
          homeExtraScore: 1,
          awayExtraScore: 0,
          homePenaltyScore: 4,
          awayPenaltyScore: 3,
        },
        audit: { version: 5 },
      }),
    ]
    const rows = buildScoreEntryRows(matches, m)
    expect(rows).toHaveLength(1)
    expect(rows[0]).toMatchObject({
      matchId: 1,
      version: 5,
      homeName: 'Alpha',
      awayName: 'Bravo',
      homeScore: '2',
      awayScore: '1',
      homeExtraScore: '1',
      awayExtraScore: '0',
      homePenaltyScore: '4',
      awayPenaltyScore: '3',
    })
  })

  it('version 不在は 0、スコア null は空文字（延長/PK も空文字）', () => {
    const m = buildParticipantNameMap(matrix)
    const matches = [
      fx({ id: 2, participants: { homeParticipantId: 10, awayParticipantId: 20 } }),
    ]
    const rows = buildScoreEntryRows(matches, m)
    expect(rows[0]!.version).toBe(0)
    expect(rows[0]!.homeScore).toBe('')
    expect(rows[0]!.awayScore).toBe('')
    expect(rows[0]!.homeExtraScore).toBe('')
    expect(rows[0]!.awayPenaltyScore).toBe('')
  })
})

describe('parseScoreInput', () => {
  it('空文字は null、非負整数はそのまま、不正値は null', () => {
    expect(parseScoreInput('')).toBeNull()
    expect(parseScoreInput('  ')).toBeNull()
    expect(parseScoreInput('3')).toBe(3)
    expect(parseScoreInput('0')).toBe(0)
    expect(parseScoreInput('-1')).toBeNull()
    expect(parseScoreInput('1.5')).toBeNull()
    expect(parseScoreInput('abc')).toBeNull()
  })
})

describe('isRowValid', () => {
  const base = makeRow({ matchId: 1 })
  it('両方空・両方整数は有効', () => {
    expect(isRowValid({ ...base })).toBe(true)
    expect(isRowValid({ ...base, homeScore: '2', awayScore: '1' })).toBe(true)
  })
  it('片方のみ入力は不正', () => {
    expect(isRowValid({ ...base, homeScore: '2', awayScore: '' })).toBe(false)
  })
  it('負数・小数・非数値は不正', () => {
    expect(isRowValid({ ...base, homeScore: '-1', awayScore: '0' })).toBe(false)
    expect(isRowValid({ ...base, homeScore: '1.5', awayScore: '0' })).toBe(false)
    expect(isRowValid({ ...base, homeScore: 'x', awayScore: '0' })).toBe(false)
  })

  it('延長/PK 欄はフラグ有効時のみ検証する', () => {
    const flags = { showExtraTime: true, showPenalties: true }
    const full = {
      ...base,
      homeScore: '1',
      awayScore: '1',
      homeExtraScore: '0',
      awayExtraScore: '0',
      homePenaltyScore: '5',
      awayPenaltyScore: '4',
    }
    expect(isRowValid(full, flags)).toBe(true)
    // 延長片方のみは不正
    expect(isRowValid({ ...full, homeExtraScore: '1', awayExtraScore: '' }, flags)).toBe(false)
    // PK 非数値は不正
    expect(isRowValid({ ...full, homePenaltyScore: 'x' }, flags)).toBe(false)
    // フラグ無効なら延長/PK の不正値は無視される（本戦のみ検証）
    expect(
      isRowValid({ ...full, homeExtraScore: 'x', homePenaltyScore: '-1' }),
    ).toBe(true)
  })
})

describe('deriveScoreEntryColumnFlags', () => {
  it('hasExtraTime/hasPenalties を真偽フラグへ写す（null/未指定は false）', () => {
    expect(deriveScoreEntryColumnFlags(null)).toEqual({
      showExtraTime: false,
      showPenalties: false,
    })
    expect(deriveScoreEntryColumnFlags({ hasExtraTime: true })).toEqual({
      showExtraTime: true,
      showPenalties: false,
    })
    expect(
      deriveScoreEntryColumnFlags({ hasExtraTime: true, hasPenalties: true }),
    ).toEqual({ showExtraTime: true, showPenalties: true })
  })
})

describe('buildBatchScorePayload', () => {
  const row = makeRow

  it('両方入力済みの行のみ抽出し version を必ず同梱する（フラグ無効時 extra/penalty は null）', () => {
    const rows = [
      row({ matchId: 1, version: 3, homeScore: '2', awayScore: '1' }),
      row({ matchId: 2, version: 7 }), // 両方未入力 → スキップ
    ]
    const payload = buildBatchScorePayload(rows)
    expect(payload).not.toBeNull()
    expect(payload!.scores).toEqual([
      {
        matchId: 1,
        homeScore: 2,
        awayScore: 1,
        homeExtraScore: null,
        awayExtraScore: null,
        homePenaltyScore: null,
        awayPenaltyScore: null,
        version: 3,
      },
    ])
  })

  it('0-0 も両方入力なら送信対象になる', () => {
    const rows = [row({ matchId: 1, version: 1, homeScore: '0', awayScore: '0' })]
    const payload = buildBatchScorePayload(rows)
    expect(payload!.scores).toEqual([
      {
        matchId: 1,
        homeScore: 0,
        awayScore: 0,
        homeExtraScore: null,
        awayExtraScore: null,
        homePenaltyScore: null,
        awayPenaltyScore: null,
        version: 1,
      },
    ])
  })

  it('フラグ有効時のみ延長/PK を同梱する（version は常に同梱）', () => {
    const rows = [
      row({
        matchId: 1,
        version: 9,
        homeScore: '1',
        awayScore: '1',
        homeExtraScore: '0',
        awayExtraScore: '0',
        homePenaltyScore: '5',
        awayPenaltyScore: '4',
      }),
    ]
    const payload = buildBatchScorePayload(rows, {
      showExtraTime: true,
      showPenalties: true,
    })
    expect(payload!.scores).toEqual([
      {
        matchId: 1,
        homeScore: 1,
        awayScore: 1,
        homeExtraScore: 0,
        awayExtraScore: 0,
        homePenaltyScore: 5,
        awayPenaltyScore: 4,
        version: 9,
      },
    ])
  })

  it('延長のみ有効なら PK は null のまま同梱しない', () => {
    const rows = [
      row({
        matchId: 1,
        version: 2,
        homeScore: '2',
        awayScore: '2',
        homeExtraScore: '1',
        awayExtraScore: '0',
        // PK は入力されていてもフラグ無効なら無視され null
        homePenaltyScore: '5',
        awayPenaltyScore: '3',
      }),
    ]
    const payload = buildBatchScorePayload(rows, {
      showExtraTime: true,
      showPenalties: false,
    })
    expect(payload!.scores[0]).toMatchObject({
      homeExtraScore: 1,
      awayExtraScore: 0,
      homePenaltyScore: null,
      awayPenaltyScore: null,
      version: 2,
    })
  })

  it('不正行が 1 つでもあれば null（保存中断）', () => {
    const rows = [
      row({ matchId: 1, version: 1, homeScore: '2', awayScore: '1' }),
      row({ matchId: 2, version: 1, homeScore: '3', awayScore: '' }), // 片方のみ
    ]
    expect(buildBatchScorePayload(rows)).toBeNull()
  })

  it('送信対象ゼロでも null でなく空 scores を返す', () => {
    const payload = buildBatchScorePayload([row({ matchId: 1 })])
    expect(payload).not.toBeNull()
    expect(payload!.scores).toEqual([])
  })
})

describe('extractStatus / isConflictError / isForbiddenError', () => {
  it('response.status / statusCode / status のいずれからも取り出す', () => {
    expect(extractStatus({ response: { status: 409 } })).toBe(409)
    expect(extractStatus({ statusCode: 403 })).toBe(403)
    expect(extractStatus({ status: 500 })).toBe(500)
    expect(extractStatus(new Error('x'))).toBeUndefined()
  })
  it('409 は衝突、403/404 は権限', () => {
    expect(isConflictError({ response: { status: 409 } })).toBe(true)
    expect(isConflictError({ response: { status: 500 } })).toBe(false)
    expect(isForbiddenError({ statusCode: 403 })).toBe(true)
    expect(isForbiddenError({ statusCode: 404 })).toBe(true)
    expect(isForbiddenError({ statusCode: 409 })).toBe(false)
  })
})

describe('buildCsvTemplate', () => {
  it('ヘッダー＋matchId入り行を出力する', () => {
    const rows: ScoreEntryRow[] = [
      makeRow({ matchId: 1, homeScore: '2', awayScore: '1' }),
      makeRow({ matchId: 2, homeName: 'C', awayName: 'D' }),
    ]
    const csv = buildCsvTemplate(rows)
    const lines = csv.trimEnd().split('\n')
    expect(lines[0]).toBe(SCORE_CSV_HEADER)
    expect(lines[1]).toBe('1,2,1')
    expect(lines[2]).toBe('2,,')
  })
})
