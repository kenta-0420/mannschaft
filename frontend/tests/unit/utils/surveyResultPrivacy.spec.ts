import { describe, it, expect } from 'vitest'
import {
  isResultWithheldForAnonymityPrivacy,
  MIN_RESPONSES_FOR_ANONYMOUS_REALTIME_RESULTS,
} from '~/utils/surveyResultPrivacy'
import type { ResultsVisibility } from '~/types/survey'

/**
 * 匿名 + リアルタイム結果のプライバシーガード（設計書 F05.4 §6 セキュリティ考慮事項）。
 *
 * 背景: `is_anonymous = TRUE` かつ `results_visibility = ALWAYS`（FE の `ALL_MEMBERS`）だと、
 * 回答者が少ないうちは「自分が回答した直後の集計の変化」から他人の回答が推測できてしまう。
 * 設計書は「回答者数が5名未満の間は集計結果を表示しない」ガードを FE に置くことを求めている
 * （API は返す。伏せるのは FE の責務）。
 *
 * ここでは閾値そのものだけでなく、**巻き添えで塞いでいないこと**（非匿名・他の公開設定）も
 * 裏取りする。ガードが広すぎると、本来見えるべき結果まで消えて機能が死ぬ。
 */
describe('isResultWithheldForAnonymityPrivacy（匿名＋リアルタイム結果のプライバシーガード）', () => {
  function guard(
    isAnonymous: boolean,
    resultsVisibility: ResultsVisibility,
    responseCount: number,
  ) {
    return isResultWithheldForAnonymityPrivacy({ isAnonymous, resultsVisibility, responseCount })
  }

  it('匿名 + ALL_MEMBERS + 回答者4名 → 結果を伏せる', () => {
    expect(guard(true, 'ALL_MEMBERS', 4)).toBe(true)
  })

  it('匿名 + ALL_MEMBERS + 回答者5名 → 結果を表示する（境界値。ちょうど5で開く）', () => {
    expect(guard(true, 'ALL_MEMBERS', 5)).toBe(false)
  })

  it('境界値は閾値定数と連動している（マジックナンバーを直書きしていない）', () => {
    const n = MIN_RESPONSES_FOR_ANONYMOUS_REALTIME_RESULTS
    // 閾値を将来調整しても、「n-1 で伏せ、n で開く」関係は保たれること
    expect(guard(true, 'ALL_MEMBERS', n - 1)).toBe(true)
    expect(guard(true, 'ALL_MEMBERS', n)).toBe(false)
  })

  it('回答者0名でも伏せる（匿名 + ALL_MEMBERS）', () => {
    expect(guard(true, 'ALL_MEMBERS', 0)).toBe(true)
  })

  // --- 巻き添えで塞いでいないことの裏取り ---

  it('非匿名 + ALL_MEMBERS + 回答者1名 → 結果を表示する（匿名でなければガードしない）', () => {
    expect(guard(false, 'ALL_MEMBERS', 1)).toBe(false)
  })

  it('匿名 + AFTER_CLOSE + 回答者1名 → 従来どおり（他の可視性は塞がない）', () => {
    expect(guard(true, 'AFTER_CLOSE', 1)).toBe(false)
  })

  it('匿名 + RESPONDENTS / CREATOR_ONLY / VIEWERS_ONLY + 少数回答 → 塞がない', () => {
    // リアルタイムに集計が動くわけではないため、本ガードの前提を満たさない
    expect(guard(true, 'RESPONDENTS', 1)).toBe(false)
    expect(guard(true, 'CREATOR_ONLY', 1)).toBe(false)
    expect(guard(true, 'VIEWERS_ONLY', 1)).toBe(false)
  })

  it('resultsVisibility 未定義でも塞がない（判定できないものを勝手に隠さない）', () => {
    expect(
      isResultWithheldForAnonymityPrivacy({
        isAnonymous: true,
        resultsVisibility: undefined,
        responseCount: 1,
      }),
    ).toBe(false)
  })
})
