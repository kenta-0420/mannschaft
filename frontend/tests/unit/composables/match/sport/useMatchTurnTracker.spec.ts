import { describe, it, expect } from 'vitest'
import {
  resolveScoreFromWinner,
  isValidTurnResult,
  aggregateTeamMatchWinner,
  boardProgressSummary,
  useMatchTurnTracker,
} from '~/composables/match/sport/useMatchTurnTracker'
import type { TurnWinnerSide } from '~/composables/match/sport/useMatchTurnTracker'

/**
 * F08.10 6-④b useMatchTurnTracker UT（sports/05_shogi.md §8.5 / sports/06_go.md §8.5）。
 *
 * 観点:
 *   TURN-001: resolveScoreFromWinner — スコア格納規約（コア §B.1.2）
 *   TURN-002: isValidTurnResult — 勝者/引分けと勝ち方の組み合わせ検証
 *   TURN-003: useMatchTurnTracker — 基本状態遷移（WAITING→IN_PROGRESS→COMPLETED）
 *   TURN-004: 勝者選択・確定
 *   TURN-005: 引分け選択・確定（千日手/持将棋/持碁）
 *   TURN-006: 任意項目（手数・コメント）の入力
 *   TURN-007: 囲碁固有（isGo・margin 入力）
 *   TURN-008: restore（既存試合の再開）
 *   TURN-009: canComplete は IN_PROGRESS かつ勝者/引分け選択済みのとき true
 *   TURN-010: aggregateTeamMatchWinner — 団体戦勝ち星集計（コア §B.6・sports/05_shogi.md §4.3）
 *   TURN-011: boardProgressSummary — n/N サマリ
 *   TURN-012: モジュール登録（isSupportedSport/resolveSportModule・SHOGI/GO）
 */

describe('resolveScoreFromWinner（コア §B.1.2 勝敗格納規約）', () => {
  it('TURN-001a: HOME 勝ち → homeScore=1 / awayScore=0', () => {
    expect(resolveScoreFromWinner('HOME')).toEqual({ homeScore: 1, awayScore: 0 })
  })

  it('TURN-001b: AWAY 勝ち → homeScore=0 / awayScore=1', () => {
    expect(resolveScoreFromWinner('AWAY')).toEqual({ homeScore: 0, awayScore: 1 })
  })

  it('TURN-001c: 引分け（null）→ homeScore=0 / awayScore=0', () => {
    expect(resolveScoreFromWinner(null)).toEqual({ homeScore: 0, awayScore: 0 })
  })
})

describe('isValidTurnResult', () => {
  it('TURN-002a: winnerSide=HOME + winMethod=RESIGNATION は有効', () => {
    expect(isValidTurnResult('HOME', 'RESIGNATION')).toBe(true)
  })

  it('TURN-002b: winnerSide=HOME + winMethod=null（未入力）は有効', () => {
    expect(isValidTurnResult('HOME', null)).toBe(true)
  })

  it('TURN-002c: 引分け + winMethod=null は有効', () => {
    expect(isValidTurnResult(null, null)).toBe(true)
  })

  it('TURN-002d: 引分け + REPETITION は無効（🟡 BE MATCH_028 整合・引分時は win_method を持てない）', () => {
    // 千日手は「引分の種類ラベル」止まりで、送信ペイロードでは win_method を落とす（buildTurnResultPayload）。
    expect(isValidTurnResult(null, 'REPETITION')).toBe(false)
  })

  it('TURN-002e: 引分け + IMPASSE は無効（🟡 BE MATCH_028 整合・引分時は win_method を持てない）', () => {
    expect(isValidTurnResult(null, 'IMPASSE')).toBe(false)
  })

  it('TURN-002f: 引分け + RESIGNATION は無効（投了は必ず勝者がいる）', () => {
    expect(isValidTurnResult(null, 'RESIGNATION')).toBe(false)
  })
})

describe('aggregateTeamMatchWinner（団体戦勝ち星集計・コア §B.6）', () => {
  it('TURN-010a: 3-0 で HOME 勝ち', () => {
    const boards: Array<{ winnerSide: TurnWinnerSide; confirmed: boolean }> = [
      { winnerSide: 'HOME', confirmed: true },
      { winnerSide: 'HOME', confirmed: true },
      { winnerSide: 'HOME', confirmed: true },
    ]
    expect(aggregateTeamMatchWinner(boards)).toBe('HOME')
  })

  it('TURN-010b: 0-3 で AWAY 勝ち', () => {
    const boards: Array<{ winnerSide: TurnWinnerSide; confirmed: boolean }> = [
      { winnerSide: 'AWAY', confirmed: true },
      { winnerSide: 'AWAY', confirmed: true },
      { winnerSide: 'AWAY', confirmed: true },
    ]
    expect(aggregateTeamMatchWinner(boards)).toBe('AWAY')
  })

  it('TURN-010c: 2-2-1引分け（HOME=4.5 / AWAY=4.5 スケール換算）→ 引分け', () => {
    // HOME 2勝=4pt, AWAY 2勝=4pt, 引分け1=各1pt → 合計 HOME=5, AWAY=5
    const boards: Array<{ winnerSide: TurnWinnerSide; confirmed: boolean }> = [
      { winnerSide: 'HOME', confirmed: true },
      { winnerSide: 'HOME', confirmed: true },
      { winnerSide: 'AWAY', confirmed: true },
      { winnerSide: 'AWAY', confirmed: true },
      { winnerSide: null, confirmed: true }, // 引分け
    ]
    expect(aggregateTeamMatchWinner(boards)).toBe(null)
  })

  it('TURN-010d: 3-2（HOME3勝 AWAY2勝）→ HOME 勝ち', () => {
    const boards: Array<{ winnerSide: TurnWinnerSide; confirmed: boolean }> = [
      { winnerSide: 'HOME', confirmed: true },
      { winnerSide: 'HOME', confirmed: true },
      { winnerSide: 'HOME', confirmed: true },
      { winnerSide: 'AWAY', confirmed: true },
      { winnerSide: 'AWAY', confirmed: true },
    ]
    expect(aggregateTeamMatchWinner(boards)).toBe('HOME')
  })

  it('TURN-010e: 未確定ボードは除外される', () => {
    const boards: Array<{ winnerSide: TurnWinnerSide; confirmed: boolean }> = [
      { winnerSide: 'HOME', confirmed: true },
      { winnerSide: 'AWAY', confirmed: false }, // 未確定
    ]
    // HOME だけ確定 → HOME 勝ち
    expect(aggregateTeamMatchWinner(boards)).toBe('HOME')
  })

  it('TURN-010f: 引分け 2 + HOME 1 → HOME 勝ち（引分け各0.5・整数スケール）', () => {
    // 引分け2=各2pt, HOME1=2pt → HOME=4, AWAY=2
    const boards: Array<{ winnerSide: TurnWinnerSide; confirmed: boolean }> = [
      { winnerSide: null, confirmed: true },
      { winnerSide: null, confirmed: true },
      { winnerSide: 'HOME', confirmed: true },
    ]
    expect(aggregateTeamMatchWinner(boards)).toBe('HOME')
  })
})

describe('boardProgressSummary（n/N）', () => {
  it('TURN-011a: 3 / 5 ボードが確定', () => {
    const boards = [
      { confirmed: true },
      { confirmed: true },
      { confirmed: true },
      { confirmed: false },
      { confirmed: false },
    ]
    expect(boardProgressSummary(boards)).toEqual({ confirmedCount: 3, totalCount: 5 })
  })

  it('TURN-011b: 全確定（5 / 5）', () => {
    const boards = Array(5).fill({ confirmed: true })
    expect(boardProgressSummary(boards)).toEqual({ confirmedCount: 5, totalCount: 5 })
  })

  it('TURN-011c: 空配列', () => {
    expect(boardProgressSummary([])).toEqual({ confirmedCount: 0, totalCount: 0 })
  })
})

describe('useMatchTurnTracker（将棋・デフォルト）', () => {
  it('TURN-003: 初期状態は WAITING', () => {
    const tracker = useMatchTurnTracker({ sport: 'SHOGI' })
    expect(tracker.trackerState.value).toBe('WAITING')
    expect(tracker.isCompleted.value).toBe(false)
    expect(tracker.winnerSide.value).toBe(null)
    expect(tracker.winMethod.value).toBe(null)
    expect(tracker.totalMoves.value).toBe(null)
    expect(tracker.isGo.value).toBe(false)
  })

  it('TURN-003: start() で IN_PROGRESS に遷移', () => {
    const tracker = useMatchTurnTracker({ sport: 'SHOGI' })
    tracker.start()
    expect(tracker.trackerState.value).toBe('IN_PROGRESS')
  })

  it('TURN-004: HOME 勝ちを選択して確定', () => {
    const tracker = useMatchTurnTracker({ sport: 'SHOGI' })
    tracker.start()
    tracker.selectHomeWin()

    expect(tracker.winnerSide.value).toBe('HOME')
    expect(tracker.resultSelected.value).toBe(true)
    expect(tracker.canComplete.value).toBe(true)

    tracker.complete()
    expect(tracker.trackerState.value).toBe('COMPLETED')
    expect(tracker.isCompleted.value).toBe(true)
    expect(tracker.resolvedScore.value).toEqual({ homeScore: 1, awayScore: 0 })
  })

  it('TURN-004: AWAY 勝ちを選択して確定', () => {
    const tracker = useMatchTurnTracker({ sport: 'SHOGI' })
    tracker.start()
    tracker.selectAwayWin()
    tracker.complete()

    expect(tracker.winnerSide.value).toBe('AWAY')
    expect(tracker.resolvedScore.value).toEqual({ homeScore: 0, awayScore: 1 })
  })

  it('TURN-005: 引分けを選択して確定（千日手）', () => {
    const tracker = useMatchTurnTracker({ sport: 'SHOGI' })
    tracker.start()
    tracker.selectDraw()

    expect(tracker.drawSelected.value).toBe(true)
    expect(tracker.winnerSide.value).toBe(null)
    expect(tracker.resultSelected.value).toBe(true)
    expect(tracker.canComplete.value).toBe(true)

    tracker.complete()
    expect(tracker.isCompleted.value).toBe(true)
    expect(tracker.isDraw.value).toBe(true)
    expect(tracker.resolvedScore.value).toEqual({ homeScore: 0, awayScore: 0 })
  })

  it('TURN-009: 勝者未選択のときは canComplete=false', () => {
    const tracker = useMatchTurnTracker({ sport: 'SHOGI' })
    tracker.start()
    // 何も選択しないと canComplete=false
    expect(tracker.canComplete.value).toBe(false)
  })

  it('TURN-009: WAITING 状態では canComplete=false', () => {
    const tracker = useMatchTurnTracker({ sport: 'SHOGI' })
    // start() を呼ばずに selectHomeWin
    tracker.selectHomeWin()
    expect(tracker.canComplete.value).toBe(false)
  })

  it('TURN-006: 勝ち方・手数・コメントを任意入力', () => {
    const tracker = useMatchTurnTracker({ sport: 'SHOGI' })
    tracker.start()
    tracker.selectHomeWin()
    tracker.setWinMethod('RESIGNATION')
    tracker.setTotalMoves(80)
    tracker.setComment('見事な投了でした')

    expect(tracker.winMethod.value).toBe('RESIGNATION')
    expect(tracker.totalMoves.value).toBe(80)
    expect(tracker.comment.value).toBe('見事な投了でした')
  })

  it('TURN-006: 0 以下の手数は null に丸める', () => {
    const tracker = useMatchTurnTracker({ sport: 'SHOGI' })
    tracker.setTotalMoves(0)
    expect(tracker.totalMoves.value).toBe(null)
    tracker.setTotalMoves(-5)
    expect(tracker.totalMoves.value).toBe(null)
  })

  it('TURN-008: restore で既存状態を復元', () => {
    const tracker = useMatchTurnTracker({ sport: 'SHOGI' })
    tracker.restore({
      state: 'COMPLETED',
      winnerSide: 'HOME',
      winMethod: 'CHECKMATE',
      totalMoves: 120,
      comment: '詰みまで指し切りました',
    })

    expect(tracker.trackerState.value).toBe('COMPLETED')
    expect(tracker.winnerSide.value).toBe('HOME')
    expect(tracker.winMethod.value).toBe('CHECKMATE')
    expect(tracker.totalMoves.value).toBe(120)
    expect(tracker.comment.value).toBe('詰みまで指し切りました')
    expect(tracker.isCompleted.value).toBe(true)
  })

  it('TURN-008: restore で引分けを復元（drawSelected=true）', () => {
    const tracker = useMatchTurnTracker({ sport: 'SHOGI' })
    tracker.restore({
      state: 'COMPLETED',
      winnerSide: null,
    })

    expect(tracker.isDraw.value).toBe(true)
    expect(tracker.drawSelected.value).toBe(true)
  })
})

describe('useMatchTurnTracker（囲碁・isGo）', () => {
  it('TURN-007: sport=GO のとき isGo=true', () => {
    const tracker = useMatchTurnTracker({ sport: 'GO' })
    expect(tracker.isGo.value).toBe(true)
  })

  it('TURN-007: 目数差（margin）を入力できる', () => {
    const tracker = useMatchTurnTracker({ sport: 'GO' })
    tracker.start()
    tracker.selectHomeWin()
    tracker.setWinMethod('POINTS_WIN')
    tracker.setMargin(5.5)

    expect(tracker.margin.value).toBe(5.5)
  })

  it('TURN-007: sport=SHOGI のとき setMargin は無効（margin=null のまま）', () => {
    const tracker = useMatchTurnTracker({ sport: 'SHOGI' })
    tracker.setMargin(5.5)
    // SHOGI では isGo=false なので margin は変化しない
    expect(tracker.margin.value).toBe(null)
  })

  it('TURN-005: 囲碁の引分けは持碁（両者 0）', () => {
    const tracker = useMatchTurnTracker({ sport: 'GO' })
    tracker.start()
    tracker.selectDraw()
    tracker.complete()

    expect(tracker.isDraw.value).toBe(true)
    expect(tracker.resolvedScore.value).toEqual({ homeScore: 0, awayScore: 0 })
  })
})
