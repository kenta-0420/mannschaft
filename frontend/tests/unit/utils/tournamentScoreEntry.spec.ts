import { describe, it, expect } from 'vitest'
import {
  buildParticipantNameMap,
  resolveParticipantName,
  buildScoreEntryRows,
  parseScoreInput,
  isRowValid,
  buildBatchScorePayload,
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
 *  - 409 衝突・403/404 権限のステータス判定
 *  - CSV テンプレ生成（matchId 入りひな形）
 */

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
  it('version を audit.version から採用し、既存スコアを入力欄初期値にする', () => {
    const m = buildParticipantNameMap(matrix)
    const matches = [
      fx({
        id: 1,
        participants: { homeParticipantId: 10, awayParticipantId: 20 },
        score: { homeScore: 2, awayScore: 1 },
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
    })
  })

  it('version 不在は 0、スコア null は空文字', () => {
    const m = buildParticipantNameMap(matrix)
    const matches = [
      fx({ id: 2, participants: { homeParticipantId: 10, awayParticipantId: 20 } }),
    ]
    const rows = buildScoreEntryRows(matches, m)
    expect(rows[0]!.version).toBe(0)
    expect(rows[0]!.homeScore).toBe('')
    expect(rows[0]!.awayScore).toBe('')
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
  const base: ScoreEntryRow = {
    matchId: 1,
    version: 0,
    homeName: 'A',
    awayName: 'B',
    homeScore: '',
    awayScore: '',
  }
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
})

describe('buildBatchScorePayload', () => {
  const row = (over: Partial<ScoreEntryRow> & { matchId: number }): ScoreEntryRow => ({
    matchId: over.matchId,
    version: over.version ?? 0,
    homeName: 'A',
    awayName: 'B',
    homeScore: over.homeScore ?? '',
    awayScore: over.awayScore ?? '',
  })

  it('両方入力済みの行のみ抽出し version を必ず同梱する', () => {
    const rows = [
      row({ matchId: 1, version: 3, homeScore: '2', awayScore: '1' }),
      row({ matchId: 2, version: 7 }), // 両方未入力 → スキップ
    ]
    const payload = buildBatchScorePayload(rows)
    expect(payload).not.toBeNull()
    expect(payload!.scores).toEqual([
      { matchId: 1, homeScore: 2, awayScore: 1, version: 3 },
    ])
  })

  it('0-0 も両方入力なら送信対象になる', () => {
    const rows = [row({ matchId: 1, version: 1, homeScore: '0', awayScore: '0' })]
    const payload = buildBatchScorePayload(rows)
    expect(payload!.scores).toEqual([
      { matchId: 1, homeScore: 0, awayScore: 0, version: 1 },
    ])
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
      { matchId: 1, version: 0, homeName: 'A', awayName: 'B', homeScore: '2', awayScore: '1' },
      { matchId: 2, version: 0, homeName: 'C', awayName: 'D', homeScore: '', awayScore: '' },
    ]
    const csv = buildCsvTemplate(rows)
    const lines = csv.trimEnd().split('\n')
    expect(lines[0]).toBe(SCORE_CSV_HEADER)
    expect(lines[1]).toBe('1,2,1')
    expect(lines[2]).toBe('2,,')
  })
})
