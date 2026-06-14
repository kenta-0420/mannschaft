import { describe, it, expect } from "vitest"
import {
  setTargetPoints,
  isSetWon,
  isDeuceState,
  nextSetState,
  useMatchSetTracker,
} from "~/composables/match/sport/useMatchSetTracker"

/**
 * F08.10 6-③b useMatchSetTracker UT（sports/04_volleyball.md §4.2 / §8.5）。
 *
 * 観点:
 *   SET-001: setTargetPoints — 通常セット 25 点・ファイナルセット 15 点
 *   SET-002: isSetWon — セット勝利条件（目標点数以上かつ 2 点差）
 *   SET-003: isDeuceState — デュース状態（目標点数 -1 以上で 2 点差未満）
 *   SET-004: nextSetState — セット確定後の状態遷移
 *   SET-005: useMatchSetTracker — セット開始・得点操作・confirmCurrentSet
 *   SET-006: best-of-5 セット勝ち判定（3 セット先取で COMPLETED）
 *   SET-007: 24-24 デュース → 26-24 でセット決着
 *   SET-008: 24-24 → 25-24 は未決着（デュース継続）
 *   SET-009: モジュール登録（isSupportedSport/resolveSportModule）
 */

describe("setTargetPoints", () => {
  it("SET-001a: best-of-5 通常セット（1〜4）は 25 点", () => {
    expect(setTargetPoints(1, 5)).toBe(25)
    expect(setTargetPoints(2, 5)).toBe(25)
    expect(setTargetPoints(3, 5)).toBe(25)
    expect(setTargetPoints(4, 5)).toBe(25)
  })

  it("SET-001b: best-of-5 ファイナルセット（第 5）は 15 点", () => {
    expect(setTargetPoints(5, 5)).toBe(15)
  })

  it("SET-001c: best-of-3 ファイナルセット（第 3）は 15 点", () => {
    expect(setTargetPoints(3, 3)).toBe(15)
    expect(setTargetPoints(1, 3)).toBe(25)
    expect(setTargetPoints(2, 3)).toBe(25)
  })
})

describe("isSetWon", () => {
  it("SET-002a: 25-23 はセット勝利", () => {
    expect(isSetWon(25, 23, 1, 5)).toBe(true)
  })

  it("SET-002b: 25-24 は 1 点差なので未決着", () => {
    expect(isSetWon(25, 24, 1, 5)).toBe(false)
  })

  it("SET-002c: 24-24 は未達かつ同点なので未決着", () => {
    expect(isSetWon(24, 24, 1, 5)).toBe(false)
  })

  it("SET-002d: 26-24 はデュース解消（2 点差）でセット勝利", () => {
    expect(isSetWon(26, 24, 1, 5)).toBe(true)
  })

  it("SET-002e: 27-25 もデュース解消", () => {
    expect(isSetWon(27, 25, 1, 5)).toBe(true)
  })

  it("SET-002f: ファイナルセット（第 5）15-13 は勝利", () => {
    expect(isSetWon(15, 13, 5, 5)).toBe(true)
  })

  it("SET-002g: ファイナルセット 15-14 は 1 点差で未決着", () => {
    expect(isSetWon(15, 14, 5, 5)).toBe(false)
  })

  it("SET-002h: ファイナルセット 16-14 はデュース解消", () => {
    expect(isSetWon(16, 14, 5, 5)).toBe(true)
  })
})

describe("isDeuceState（§4.2 デュース判定）", () => {
  it("SET-003a: 24-24 は通常セットのデュース", () => {
    expect(isDeuceState(24, 24, 1, 5)).toBe(true)
  })

  it("SET-003b: 25-24 はデュース（2 点差ついていない）", () => {
    expect(isDeuceState(25, 24, 1, 5)).toBe(true)
  })

  it("SET-003c: 25-23 はセット勝利確定（デュースではない）", () => {
    expect(isDeuceState(25, 23, 1, 5)).toBe(false)
  })

  it("SET-003d: 26-24 はセット勝利確定（デュース解消）", () => {
    expect(isDeuceState(26, 24, 1, 5)).toBe(false)
  })

  it("SET-003e: 23-24 はまだデュースに達していない（23 < 24）", () => {
    expect(isDeuceState(23, 24, 1, 5)).toBe(false)
  })

  it("SET-003f: ファイナルセット 14-14 はデュース", () => {
    expect(isDeuceState(14, 14, 5, 5)).toBe(true)
  })
})

describe("nextSetState", () => {
  it("SET-004a: WAITING → SET_1", () => {
    expect(nextSetState("WAITING", 3, 0, 0)).toBe("SET_1")
  })

  it("SET-004b: SET_1 → SET_2（先取未到達）", () => {
    expect(nextSetState("SET_1", 3, 1, 0)).toBe("SET_2")
  })

  it("SET-004c: SET_2 → COMPLETED（3 セット先取＝home 3 win）", () => {
    expect(nextSetState("SET_2", 3, 3, 0)).toBe("COMPLETED")
  })

  it("SET-004d: SET_3 → COMPLETED（away 3 セット先取）", () => {
    expect(nextSetState("SET_3", 3, 0, 3)).toBe("COMPLETED")
  })

  it("SET-004e: SET_4 → SET_5（先取未到達）", () => {
    expect(nextSetState("SET_4", 3, 2, 2)).toBe("SET_5")
  })
})

describe("useMatchSetTracker（統合・§8.5）", () => {
  it("SET-005: セット開始・得点操作・基本フロー", () => {
    const tracker = useMatchSetTracker({ bestOf: 5 })

    expect(tracker.trackerState.value).toBe("WAITING")
    expect(tracker.currentSetNumber.value).toBeNull()

    tracker.startFirstSet()
    expect(tracker.trackerState.value).toBe("SET_1")
    expect(tracker.currentSetNumber.value).toBe(1)
    expect(tracker.currentSet.value?.homePoints).toBe(0)

    // 得点入力
    tracker.setHomePoints(25)
    tracker.setAwayPoints(23)
    expect(tracker.currentSet.value?.homePoints).toBe(25)
    expect(tracker.canConfirmSet.value).toBe(true)

    tracker.confirmCurrentSet()
    expect(tracker.trackerState.value).toBe("SET_2")
    expect(tracker.homeWins.value).toBe(1)
  })

  it("SET-007: 24-24 デュース → 26-24 でセット決着", () => {
    const tracker = useMatchSetTracker({ bestOf: 5 })
    tracker.startFirstSet()

    tracker.setHomePoints(24)
    tracker.setAwayPoints(24)
    expect(tracker.isDeuce.value).toBe(true)
    expect(tracker.canConfirmSet.value).toBe(false)

    // 25-24 はまだデュース
    tracker.setHomePoints(25)
    expect(tracker.isDeuce.value).toBe(true)
    expect(tracker.canConfirmSet.value).toBe(false)

    // 26-24 でデュース解消・確定可能
    tracker.setHomePoints(26)
    expect(tracker.isDeuce.value).toBe(false)
    expect(tracker.canConfirmSet.value).toBe(true)

    tracker.confirmCurrentSet()
    expect(tracker.homeWins.value).toBe(1)
    expect(tracker.trackerState.value).toBe("SET_2")
  })

  it("SET-008: 24-24 → 25-24 は未決着（デュース継続）", () => {
    const tracker = useMatchSetTracker({ bestOf: 5 })
    tracker.startFirstSet()

    tracker.setHomePoints(24)
    tracker.setAwayPoints(24)
    expect(tracker.canConfirmSet.value).toBe(false)

    tracker.setHomePoints(25)
    // 25-24 = まだデュース（1 点差）
    expect(tracker.canConfirmSet.value).toBe(false)
    expect(tracker.isDeuce.value).toBe(true)
  })

  it("SET-006: best-of-5 で 3 セット先取したら COMPLETED", () => {
    const tracker = useMatchSetTracker({ bestOf: 5 })
    tracker.startFirstSet()

    // セット 1
    tracker.setHomePoints(25)
    tracker.setAwayPoints(20)
    tracker.confirmCurrentSet()
    expect(tracker.trackerState.value).toBe("SET_2")

    // セット 2
    tracker.setHomePoints(25)
    tracker.setAwayPoints(18)
    tracker.confirmCurrentSet()
    expect(tracker.trackerState.value).toBe("SET_3")

    // セット 3（3 セット目で決着）
    tracker.setHomePoints(25)
    tracker.setAwayPoints(15)
    tracker.confirmCurrentSet()
    expect(tracker.trackerState.value).toBe("COMPLETED")
    expect(tracker.isCompleted.value).toBe(true)
    expect(tracker.homeWins.value).toBe(3)
    expect(tracker.awayWins.value).toBe(0)
  })

  it("SET-006b: 2-2 タイで第 5 セットへ進む", () => {
    const tracker = useMatchSetTracker({ bestOf: 5 })
    tracker.startFirstSet()

    // home 1セット目獲得
    tracker.setHomePoints(25)
    tracker.setAwayPoints(20)
    tracker.confirmCurrentSet()
    // away 2セット目獲得
    tracker.setAwayPoints(25)
    tracker.setHomePoints(20)
    tracker.confirmCurrentSet()
    // home 3セット目獲得
    tracker.setHomePoints(25)
    tracker.setAwayPoints(22)
    tracker.confirmCurrentSet()
    // away 4セット目獲得
    tracker.setAwayPoints(25)
    tracker.setHomePoints(20)
    tracker.confirmCurrentSet()

    expect(tracker.trackerState.value).toBe("SET_5")
    expect(tracker.homeWins.value).toBe(2)
    expect(tracker.awayWins.value).toBe(2)

    // 第 5 セット（目標 15 点）
    expect(tracker.currentSetNumber.value).toBe(5)
    tracker.setHomePoints(15)
    tracker.setAwayPoints(13)
    expect(tracker.canConfirmSet.value).toBe(true)
    tracker.confirmCurrentSet()
    expect(tracker.isCompleted.value).toBe(true)
  })

  it("+1 / -1 操作が正しく機能する", () => {
    const tracker = useMatchSetTracker()
    tracker.startFirstSet()

    tracker.incrementHome()
    tracker.incrementHome()
    tracker.incrementAway()
    expect(tracker.currentSet.value?.homePoints).toBe(2)
    expect(tracker.currentSet.value?.awayPoints).toBe(1)

    tracker.decrementHome()
    expect(tracker.currentSet.value?.homePoints).toBe(1)

    tracker.decrementAway()
    tracker.decrementAway() // 0 未満にはならない
    expect(tracker.currentSet.value?.awayPoints).toBe(0)
  })

  it("確定前は canConfirmSet=false（§4.2）", () => {
    const tracker = useMatchSetTracker()
    tracker.startFirstSet()

    // 25-24 は 1 点差でデュース
    tracker.setHomePoints(25)
    tracker.setAwayPoints(24)
    expect(tracker.canConfirmSet.value).toBe(false)

    // 0-0 も未達
    tracker.setHomePoints(0)
    tracker.setAwayPoints(0)
    expect(tracker.canConfirmSet.value).toBe(false)
  })
})
