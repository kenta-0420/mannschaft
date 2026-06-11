import { describe, it, expect } from 'vitest'
import {
  TOURNAMENT_VISIBILITY_LEVELS,
  isTournamentVisibility,
  buildMatrixGrid,
  matrixCellKey,
  matrixCellScoreText,
  buildRankingChartData,
  rankingValue,
  rankingValueText,
  parseTimeToSeconds,
} from '~/utils/tournamentStandings'
import type { TournamentMatrix, IndividualRanking } from '~/types/tournament'

/**
 * F08.7 順位UI Wave1: 順位表 / マトリクス / ランキング表示の純関数ユニットテスト。
 *
 *  VIS-001: 6 レベルが正規の enum 名と一致する
 *  VIS-002/003: 型ガードが正規値を通し、不正値を弾く
 *  MX-001..005: マトリクスのグリッド整形（行×列・対角・未対戦・キー形式・空入力）
 *  RK-001..005: ランキングのチャート整形・値抽出・表記
 */

describe('TOURNAMENT_VISIBILITY_LEVELS / isTournamentVisibility', () => {
  it('VIS-001: 6 レベルが BE enum 名と一致する', () => {
    expect(TOURNAMENT_VISIBILITY_LEVELS).toEqual([
      'PUBLIC',
      'SUPPORTERS_AND_ABOVE',
      'MEMBERS_AND_ABOVE',
      'ADMINS_AND_ABOVE',
      'SCOPE_AFFILIATED',
      'PARTICIPANTS_ONLY',
    ])
  })

  it('VIS-002: 正規の可視性値を通す', () => {
    expect(isTournamentVisibility('PUBLIC')).toBe(true)
    expect(isTournamentVisibility('PARTICIPANTS_ONLY')).toBe(true)
  })

  it('VIS-003: 不正値・null・旧値を弾く', () => {
    expect(isTournamentVisibility('MEMBERS_ONLY')).toBe(false) // 旧値
    expect(isTournamentVisibility(null)).toBe(false)
    expect(isTournamentVisibility(123)).toBe(false)
    expect(isTournamentVisibility('')).toBe(false)
  })
})

describe('matrixCellKey', () => {
  it('MX-001: BE と同じ `${home}_${away}` 形式のキーを生成する', () => {
    expect(matrixCellKey(3, 7)).toBe('3_7')
  })
})

describe('buildMatrixGrid', () => {
  const matrix: TournamentMatrix = {
    participants: [
      { participantId: 1, teamId: 10, teamName: 'A' },
      { participantId: 2, teamId: 20, teamName: 'B' },
      { participantId: 3, teamId: 30, teamName: 'C' },
    ],
    cells: {
      // A(home) vs B(away): 2-1
      '1_2': { matchId: 100, homeScore: 2, awayScore: 1, result: 'HOME_WIN' },
      // B(home) vs C(away): スコア未確定
      '2_3': { matchId: 101, homeScore: null, awayScore: null, result: 'SCHEDULED' },
    },
  }

  it('MX-002: 行×列が participants 順で構築され、列ヘッダも一致する', () => {
    const grid = buildMatrixGrid(matrix)
    expect(grid.columns.map((c) => c.teamName)).toEqual(['A', 'B', 'C'])
    expect(grid.rows.map((r) => r.teamName)).toEqual(['A', 'B', 'C'])
    expect(grid.rows[0]!.cells).toHaveLength(3)
  })

  it('MX-003: 対角（home==away）は isDiagonal=true・cell=null', () => {
    const grid = buildMatrixGrid(matrix)
    // A 行の A 列（index 0）が対角
    expect(grid.rows[0]!.cells[0]!.isDiagonal).toBe(true)
    expect(grid.rows[0]!.cells[0]!.cell).toBeNull()
  })

  it('MX-004: 既存セルを正しい交点に配置し、未対戦は cell=null', () => {
    const grid = buildMatrixGrid(matrix)
    // A(行0) vs B(列1) は 2-1
    const ab = grid.rows[0]!.cells[1]!
    expect(ab.isDiagonal).toBe(false)
    expect(ab.cell?.matchId).toBe(100)
    expect(matrixCellScoreText(ab.cell)).toBe('2-1')
    // A(行0) vs C(列2) は未対戦
    const ac = grid.rows[0]!.cells[2]!
    expect(ac.cell).toBeNull()
    // B(行1) vs C(列2) はスコア未確定 → 空文字
    const bc = grid.rows[1]!.cells[2]!
    expect(bc.cell?.matchId).toBe(101)
    expect(matrixCellScoreText(bc.cell)).toBe('')
  })

  it('MX-005: null/空入力は空グリッドを返す', () => {
    expect(buildMatrixGrid(null)).toEqual({ columns: [], rows: [] })
    expect(buildMatrixGrid(undefined)).toEqual({ columns: [], rows: [] })
    expect(buildMatrixGrid({ participants: [], cells: {} })).toEqual({ columns: [], rows: [] })
  })
})

describe('rankingValue / rankingValueText', () => {
  function r(stat: Partial<NonNullable<IndividualRanking['stat']>>, rank?: number): IndividualRanking {
    return { rank, stat: { statKey: 'goals', ...stat } }
  }

  it('RK-001: int > decimal > time の優先で値を採用する', () => {
    expect(rankingValue(r({ totalValueInt: 5, totalValueDecimal: 9 }))).toBe(5)
    expect(rankingValue(r({ totalValueDecimal: 3.5 }))).toBe(3.5)
    // time 系は "HH:mm:ss" 文字列 → 秒換算（00:01:30 = 90 秒）
    expect(rankingValue(r({ totalValueTime: '00:01:30' }))).toBe(90)
  })

  it('RK-002: すべて null / stat 無しは 0', () => {
    expect(rankingValue(r({}))).toBe(0)
    expect(rankingValue({ rank: 1 })).toBe(0)
  })

  it('RK-003: 単位付きテキストを生成する', () => {
    expect(rankingValueText(r({ totalValueInt: 7 }), '点')).toBe('7点')
    expect(rankingValueText(r({ totalValueInt: 7 }))).toBe('7')
  })

  it('RK-006: time 系 stat はチャート値を秒換算し、テキストは "HH:mm:ss" 文字列をそのまま表示する', () => {
    // 最速タイム 1分23.456秒 = 83.456 秒
    const timeRanking = r({ totalValueTime: '00:01:23.456' })
    expect(rankingValue(timeRanking)).toBeCloseTo(83.456, 3)
    // テキストは秒数ではなく人間可読のタイム表記（単位は付けない）
    expect(rankingValueText(timeRanking, '秒')).toBe('00:01:23.456')

    // 1時間 = 3600 秒
    expect(rankingValue(r({ totalValueTime: '01:00:00' }))).toBe(3600)
  })

  it('RK-007: time 文字列が壊れている場合は 0（チャートを NaN で壊さない）', () => {
    expect(rankingValue(r({ totalValueTime: 'not-a-time' }))).toBe(0)
    expect(rankingValue(r({ totalValueTime: '12:34' }))).toBe(0) // 3 セグメントでない
  })
})

describe('parseTimeToSeconds', () => {
  it('TIME-001: "HH:mm:ss" を秒へ変換する', () => {
    expect(parseTimeToSeconds('00:00:00')).toBe(0)
    expect(parseTimeToSeconds('00:01:30')).toBe(90)
    expect(parseTimeToSeconds('01:02:03')).toBe(3723)
  })

  it('TIME-002: 小数秒（"HH:mm:ss.SSS"）も吸収する', () => {
    expect(parseTimeToSeconds('00:00:01.500')).toBeCloseTo(1.5, 3)
  })

  it('TIME-003: null / 不正値は null を返す', () => {
    expect(parseTimeToSeconds(null)).toBeNull()
    expect(parseTimeToSeconds(undefined)).toBeNull()
    expect(parseTimeToSeconds('1:2')).toBeNull()
    expect(parseTimeToSeconds('aa:bb:cc')).toBeNull()
  })
})

describe('buildRankingChartData', () => {
  const rankings: IndividualRanking[] = [
    { rank: 2, context: { userId: 20 }, stat: { statKey: 'goals', totalValueInt: 5 } },
    { rank: 1, context: { userId: 10 }, stat: { statKey: 'goals', totalValueInt: 9 } },
    { rank: 3, context: { userId: 30 }, stat: { statKey: 'goals', totalValueInt: 3 } },
  ]

  it('RK-004: rank 昇順に並べ、解決名/フォールバック#userId をラベルにする', () => {
    const resolve = (id: number | undefined) => (id === 10 ? '田中' : undefined)
    const data = buildRankingChartData(rankings, '得点', resolve)
    expect(data.labels).toEqual(['田中', '#20', '#30'])
    expect(data.datasets[0]!.label).toBe('得点')
    expect(data.datasets[0]!.data).toEqual([9, 5, 3])
  })

  it('RK-005: limit で上位のみに絞る', () => {
    const data = buildRankingChartData(rankings, '得点', () => undefined, 2)
    expect(data.labels).toEqual(['#10', '#20'])
    expect(data.datasets[0]!.data).toEqual([9, 5])
  })
})
