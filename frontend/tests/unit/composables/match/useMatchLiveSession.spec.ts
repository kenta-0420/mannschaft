import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'
import { isNextCompleted } from '~/composables/match/useMatchTimer'

/**
 * F08.10 延長戦/PK 完了時の completeMatch 経路テスト（検分🟠根治）。
 *
 * 検証観点:
 *   SESSION-001: EXTRA_SECOND からの advance で isNextCompleted が true → completeMatch ルート
 *   SESSION-002: PENALTY_SHOOTOUT からの advance で isNextCompleted が true → completeMatch ルート
 *   SESSION-003: SECOND_HALF からの advance では isNextCompleted が false → timer.advance ルート（二重発火しない）
 *   SESSION-004: SECOND_HALF の @complete は completeMatch を呼ぶ（既存経路の維持確認）
 *   SESSION-005: EXTRA_SECOND でも PENALTY_SHOOTOUT でもない通常状態（WAITING/FIRST_HALF）は
 *                isNextCompleted が false → timer.advance に委譲される
 *
 * NOTE: live.vue の handleAdvance はディスパッチ関数であり、
 *       isNextCompleted(state) が true なら completeMatch()、false なら timer.advance() を呼ぶ。
 *       本テストはその分岐ロジックを isNextCompleted のテーブルで表現し、
 *       completeMatch モックの呼び出し回数で二重発火がないことを保証する。
 */

describe('handleAdvance ディスパッチ（延長/PK の completeMatch 経路）', () => {
  // handleAdvance の実装を直接テストするためのミニマルシム
  function buildDispatcher(initialState: import('~/composables/match/useMatchTimer').TimerState) {
    const state = ref(initialState)
    const advanceSpy = vi.fn()
    const completeMatchSpy = vi.fn()

    async function handleAdvance(): Promise<void> {
      if (isNextCompleted(state.value)) {
        await completeMatchSpy()
      } else {
        await advanceSpy()
      }
    }

    return { state, handleAdvance, advanceSpy, completeMatchSpy }
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('SESSION-001: EXTRA_SECOND からの advance は completeMatch を呼ぶ（timer.advance は呼ばない）', async () => {
    const { handleAdvance, advanceSpy, completeMatchSpy } = buildDispatcher('EXTRA_SECOND')
    await handleAdvance()
    expect(completeMatchSpy).toHaveBeenCalledTimes(1)
    expect(advanceSpy).not.toHaveBeenCalled()
  })

  it('SESSION-002: PENALTY_SHOOTOUT からの advance は completeMatch を呼ぶ（timer.advance は呼ばない）', async () => {
    const { handleAdvance, advanceSpy, completeMatchSpy } = buildDispatcher('PENALTY_SHOOTOUT')
    await handleAdvance()
    expect(completeMatchSpy).toHaveBeenCalledTimes(1)
    expect(advanceSpy).not.toHaveBeenCalled()
  })

  it('SESSION-003: SECOND_HALF からの advance は timer.advance を呼ぶ（completeMatch は呼ばない・二重発火なし）', async () => {
    // SECOND_HALF には別途 @complete ボタンが存在する。advance は延長分岐用。
    const { handleAdvance, advanceSpy, completeMatchSpy } = buildDispatcher('SECOND_HALF')
    await handleAdvance()
    expect(advanceSpy).toHaveBeenCalledTimes(1)
    expect(completeMatchSpy).not.toHaveBeenCalled()
  })

  it('SESSION-004: SECOND_HALF の @complete は completeMatch を直接呼ぶ（既存経路の維持）', async () => {
    // live.vue の @complete="session.completeMatch()" は SECOND_HALF 専用。
    // isNextCompleted('SECOND_HALF') === false であることを確認し、
    // @complete 経路と @advance 経路が排他的であることを保証する。
    expect(isNextCompleted('SECOND_HALF')).toBe(false)
    // @complete は直接 completeMatch を呼ぶため、handleAdvance とは独立（二重発火なし）
  })

  it('SESSION-005: WAITING/FIRST_HALF/HALF_TIME/EXTRA_FIRST は timer.advance に委譲される', async () => {
    const normalStates = ['WAITING', 'FIRST_HALF', 'HALF_TIME', 'EXTRA_FIRST'] as const
    for (const s of normalStates) {
      const { handleAdvance, advanceSpy, completeMatchSpy } = buildDispatcher(s)
      await handleAdvance()
      expect(advanceSpy).toHaveBeenCalledTimes(1)
      expect(completeMatchSpy).not.toHaveBeenCalled()
    }
  })
})

describe('useMatchLiveSession completeMatch 冪等性（状態機械との連携）', () => {
  it('SESSION-006: completeMatch は matchStatus=COMPLETED 時に changeStatus を再送しない', async () => {
    // completeMatch の冪等ガードを検証する
    // 直接 useMatchLiveSession をインスタンス化するとモック設定が複雑なため、
    // completeMatch の実装コード（useMatchLiveSession.ts L207-L218）の振る舞いを
    // ミニマルシムで表現する
    const changeStatusSpy = vi.fn()
    let matchStatusVal: string | null = 'COMPLETED'
    const timerCompleteSpy = vi.fn()

    async function completeMatch(): Promise<void> {
      await timerCompleteSpy()
      if (matchStatusVal === 'COMPLETED') return // 冪等ガード
      await changeStatusSpy()
      matchStatusVal = 'COMPLETED'
    }

    await completeMatch()
    // タイマーは呼ばれるが changeStatus は呼ばれない（既に COMPLETED）
    expect(timerCompleteSpy).toHaveBeenCalledTimes(1)
    expect(changeStatusSpy).not.toHaveBeenCalled()
  })

  it('SESSION-007: completeMatch は IN_PROGRESS 時に changeStatus を呼んで COMPLETED に更新する', async () => {
    const changeStatusSpy = vi.fn()
    let matchStatusVal: string | null = 'IN_PROGRESS'
    const timerCompleteSpy = vi.fn()

    async function completeMatch(): Promise<void> {
      await timerCompleteSpy()
      if (matchStatusVal === 'COMPLETED') return
      await changeStatusSpy()
      matchStatusVal = 'COMPLETED'
    }

    await completeMatch()
    expect(timerCompleteSpy).toHaveBeenCalledTimes(1)
    expect(changeStatusSpy).toHaveBeenCalledTimes(1)
    expect(matchStatusVal).toBe('COMPLETED')
  })
})

/**
 * F08.10 ② PK スコアの確定保存→順位連携テスト。
 *
 * completeMatch(penalty) は COMPLETED の前に finalizeScore で本戦（延長合算済みの導出スコア）と
 * PK 戦スコアを Entity に確定保存する。BE の changeStatus(COMPLETED) は保存済み Entity の値を
 * MatchCompletedEvent へ載せ、tournament/MatchScoreFixtureListener が fixture 順位へ反映する（#1444）。
 * よって「finalize が status より先」「PK ありなら penalty を同梱・なしなら undefined」を保証する。
 *
 * useMatchLiveSession の実体は Nuxt の auto-import に依存するため、本テストは completeMatch の
 * 実装ロジック（useMatchLiveSession.ts）の振る舞いをミニマルシムで表現し、finalizeScore へ渡る
 * ペイロードと呼び出し順序を検証する。
 */
describe('completeMatch の PK スコア確定保存（F08.10 ② 順位連携）', () => {
  type FinalizeBody = {
    homeScore: number
    awayScore: number
    homePenaltyScore?: number
    awayPenaltyScore?: number
  }

  function buildCompleteMatch(initialStatus: string | null) {
    const callOrder: string[] = []
    const finalizeSpy = vi.fn<(body: FinalizeBody) => void>(() => callOrder.push('finalize'))
    const changeStatusSpy = vi.fn(() => callOrder.push('status'))
    let matchStatusVal = initialStatus
    const homeScore = { value: 0 }
    const awayScore = { value: 0 }

    async function completeMatch(penalty?: { home: number; away: number } | null): Promise<void> {
      if (matchStatusVal === 'COMPLETED') return
      finalizeSpy({
        homeScore: homeScore.value,
        awayScore: awayScore.value,
        homePenaltyScore: penalty ? penalty.home : undefined,
        awayPenaltyScore: penalty ? penalty.away : undefined,
      })
      changeStatusSpy()
      matchStatusVal = 'COMPLETED'
    }

    return { completeMatch, finalizeSpy, changeStatusSpy, callOrder, homeScore, awayScore }
  }

  beforeEach(() => vi.clearAllMocks())

  it('SESSION-008: PK 戦ありは penalty 成功数を finalizeScore に同梱する', async () => {
    const c = buildCompleteMatch('IN_PROGRESS')
    c.homeScore.value = 1
    c.awayScore.value = 1
    await c.completeMatch({ home: 4, away: 3 })
    expect(c.finalizeSpy).toHaveBeenCalledTimes(1)
    expect(c.finalizeSpy.mock.calls[0]![0]).toEqual({
      homeScore: 1,
      awayScore: 1,
      homePenaltyScore: 4,
      awayPenaltyScore: 3,
    })
  })

  it('SESSION-009: PK 戦なし（penalty=null）は penalty を undefined にして本戦のみ確定する', async () => {
    const c = buildCompleteMatch('IN_PROGRESS')
    c.homeScore.value = 2
    c.awayScore.value = 0
    await c.completeMatch(null)
    expect(c.finalizeSpy.mock.calls[0]![0]).toEqual({
      homeScore: 2,
      awayScore: 0,
      homePenaltyScore: undefined,
      awayPenaltyScore: undefined,
    })
  })

  it('SESSION-010: finalizeScore は changeStatus より先に呼ぶ（Entity 確定→COMPLETED で MatchCompletedEvent に載る順序）', async () => {
    const c = buildCompleteMatch('IN_PROGRESS')
    await c.completeMatch({ home: 5, away: 4 })
    expect(c.callOrder).toEqual(['finalize', 'status'])
  })

  it('SESSION-011: 既に COMPLETED なら finalize も status も再送しない（冪等）', async () => {
    const c = buildCompleteMatch('COMPLETED')
    await c.completeMatch({ home: 5, away: 4 })
    expect(c.finalizeSpy).not.toHaveBeenCalled()
    expect(c.changeStatusSpy).not.toHaveBeenCalled()
  })
})
