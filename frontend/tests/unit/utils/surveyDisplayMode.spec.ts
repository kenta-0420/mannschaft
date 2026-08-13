import { describe, it, expect } from 'vitest'
import {
  resolveSurveyDisplayMode,
  shouldShowRespondCta,
  type SurveyDisplayModeInput,
  type RespondCtaInput,
} from '~/utils/surveyDisplayMode'
import { isResultWithheldForAnonymityPrivacy } from '~/utils/surveyResultPrivacy'
import type { ResultsVisibility, SurveyStatus } from '~/types/survey'

/**
 * アンケート詳細画面の表示モード決定（分岐の優先順位）。
 *
 * 本丸は「プライバシーガードが回答導線を食い潰さないこと」。
 * 匿名＋ALL_MEMBERS＋PUBLISHED は**公開直後は必ず回答0件**であり、伏せる画面を
 * `response` より先に判定すると未回答者が回答フォームへ到達できず、
 * 「誰も回答できない → 閾値に達しない → 永久に解除されない」という詰みになる。
 */
describe('resolveSurveyDisplayMode（表示モードの優先順位）', () => {
  function mode(overrides: Partial<SurveyDisplayModeInput> = {}) {
    return resolveSurveyDisplayMode({
      status: 'PUBLISHED',
      canViewResults: false,
      resultsWithheldForPrivacy: false,
      hasResponded: false,
      allowMultipleSubmissions: false,
      responseRequested: false,
      ...overrides,
    })
  }

  /**
   * 実際のアンケート属性から、画面と同じ経路（プライバシー判定 → モード決定）で解決する。
   * ガードの条件式と表示モードの分岐が噛み合っていることまで含めて検証するため、
   * `resultsWithheldForPrivacy` を手で与えず本物の判定関数から導出する。
   */
  function modeForSurvey(params: {
    status: SurveyStatus
    isAnonymous: boolean
    resultsVisibility: ResultsVisibility
    responseCount: number
    hasResponded: boolean
    /** ALL_MEMBERS は全メンバーが閲覧権限を持つ。 */
    canViewResults?: boolean
    allowMultipleSubmissions?: boolean
    responseRequested?: boolean
  }) {
    return resolveSurveyDisplayMode({
      status: params.status,
      canViewResults: params.canViewResults ?? true,
      resultsWithheldForPrivacy: isResultWithheldForAnonymityPrivacy({
        isAnonymous: params.isAnonymous,
        resultsVisibility: params.resultsVisibility,
        responseCount: params.responseCount,
      }),
      hasResponded: params.hasResponded,
      allowMultipleSubmissions: params.allowMultipleSubmissions ?? false,
      responseRequested: params.responseRequested ?? false,
    })
  }

  // === 本丸: 詰みの再発防止 ===

  it('匿名＋ALL_MEMBERS＋回答0件＋未回答 → 回答フォームに到達できる（詰みの再発防止）', () => {
    // 公開直後の匿名リアルタイムアンケート。ここで説明画面を出すと誰も回答できず、
    // 回答数が閾値に達しないためガードが永久に解除されない。
    expect(
      modeForSurvey({
        status: 'PUBLISHED',
        isAnonymous: true,
        resultsVisibility: 'ALL_MEMBERS',
        responseCount: 0,
        hasResponded: false,
      }),
    ).toBe('response')
  })

  it('回答者1〜4名の間も、未回答者は回答フォームに到達できる', () => {
    for (const responseCount of [1, 2, 3, 4]) {
      expect(
        modeForSurvey({
          status: 'PUBLISHED',
          isAnonymous: true,
          resultsVisibility: 'ALL_MEMBERS',
          responseCount,
          hasResponded: false,
        }),
        `回答${responseCount}件のとき未回答者は回答できること`,
      ).toBe('response')
    }
  })

  // === ガードの効力が維持されていること ===

  it('匿名＋ALL_MEMBERS＋回答4件＋回答済み → 集計を伏せ、理由を示す', () => {
    expect(
      modeForSurvey({
        status: 'PUBLISHED',
        isAnonymous: true,
        resultsVisibility: 'ALL_MEMBERS',
        responseCount: 4,
        hasResponded: true,
      }),
    ).toBe('results-withheld-privacy')
  })

  it('匿名＋ALL_MEMBERS＋回答5件 → 集計を表示する（境界値・回答済/未回答とも）', () => {
    expect(
      modeForSurvey({
        status: 'PUBLISHED',
        isAnonymous: true,
        resultsVisibility: 'ALL_MEMBERS',
        responseCount: 5,
        hasResponded: true,
      }),
    ).toBe('results')
    expect(
      modeForSurvey({
        status: 'PUBLISHED',
        isAnonymous: true,
        resultsVisibility: 'ALL_MEMBERS',
        responseCount: 5,
        hasResponded: false,
      }),
    ).toBe('results')
  })

  it('非匿名＋ALL_MEMBERS＋回答1件 → 従来どおり集計が見える（巻き添え無し）', () => {
    expect(
      modeForSurvey({
        status: 'PUBLISHED',
        isAnonymous: false,
        resultsVisibility: 'ALL_MEMBERS',
        responseCount: 1,
        hasResponded: false,
      }),
    ).toBe('results')
  })

  it('匿名＋AFTER_CLOSE＋回答1件（CLOSED）→ 従来どおり集計が見える（他の可視性は塞がない）', () => {
    expect(
      modeForSurvey({
        status: 'CLOSED',
        isAnonymous: true,
        resultsVisibility: 'AFTER_CLOSE',
        responseCount: 1,
        hasResponded: false,
      }),
    ).toBe('results')
  })

  it('CLOSED で伏せられている場合は説明画面（もう回答手段が無いため）', () => {
    expect(
      modeForSurvey({
        status: 'CLOSED',
        isAnonymous: true,
        resultsVisibility: 'ALL_MEMBERS',
        responseCount: 2,
        hasResponded: false,
      }),
    ).toBe('results-withheld-privacy')
  })

  // === 既存の分岐が壊れていないこと ===

  it('DRAFT は常に draft', () => {
    expect(mode({ status: 'DRAFT', canViewResults: true })).toBe('draft')
    expect(mode({ status: 'DRAFT', canViewResults: false })).toBe('draft')
    // 伏せる条件が立っていても DRAFT が優先される
    expect(mode({ status: 'DRAFT', canViewResults: true, resultsWithheldForPrivacy: true })).toBe(
      'draft',
    )
  })

  it('結果閲覧権限があり伏せる条件が無ければ results', () => {
    expect(mode({ canViewResults: true })).toBe('results')
  })

  it('結果閲覧権限が無ければ PUBLISHED は response・CLOSED は closed-no-permission', () => {
    expect(mode({ canViewResults: false, status: 'PUBLISHED' })).toBe('response')
    expect(mode({ canViewResults: false, status: 'CLOSED' })).toBe('closed-no-permission')
  })

  // === 結果画面から回答フォームへ移れること ===

  it('結果画面で回答導線が押されたら回答フォームへ移る', () => {
    expect(
      modeForSurvey({
        status: 'PUBLISHED',
        isAnonymous: false,
        resultsVisibility: 'ALL_MEMBERS',
        responseCount: 3,
        hasResponded: false,
        responseRequested: true,
      }),
    ).toBe('response')
  })

  it('導線を出せない状態（CLOSED）では回答要求が効かない', () => {
    expect(
      modeForSurvey({
        status: 'CLOSED',
        isAnonymous: false,
        resultsVisibility: 'ALL_MEMBERS',
        responseCount: 3,
        hasResponded: false,
        responseRequested: true,
      }),
    ).toBe('results')
  })

  it('回答済み＋複数回答不可では回答要求が効かない', () => {
    expect(
      modeForSurvey({
        status: 'PUBLISHED',
        isAnonymous: false,
        resultsVisibility: 'ALL_MEMBERS',
        responseCount: 3,
        hasResponded: true,
        allowMultipleSubmissions: false,
        responseRequested: true,
      }),
    ).toBe('results')
  })
})

/**
 * 結果画面の回答導線（CTA）の出し分け。
 *
 * `ALL_MEMBERS`（BE の `ALWAYS`）は未回答者も結果画面に直接来る仕様のため、
 * ここに導線が無いと**未回答者が結果画面に固定され、UI から回答を集めきれない**。
 * 非匿名なら公開直後から、匿名でもガードが外れた瞬間から詰む。
 */
describe('shouldShowRespondCta（結果画面の回答導線）', () => {
  function cta(overrides: Partial<RespondCtaInput> = {}) {
    return shouldShowRespondCta({
      status: 'PUBLISHED',
      hasResponded: false,
      allowMultipleSubmissions: false,
      ...overrides,
    })
  }

  it('非匿名＋ALL_MEMBERS＋PUBLISHED＋未回答 → 回答導線が出る（今回の本丸）', () => {
    // 非匿名の ALWAYS は公開直後から全員が results に落ちる。導線が無いと誰も回答できない。
    expect(cta({ hasResponded: false })).toBe(true)
  })

  it('匿名＋ALL_MEMBERS＋回答5件以上＋未回答 → 回答導線が出る（ガードが外れた後も詰まない）', () => {
    // プライバシーガードが外れた瞬間に残りの未回答者が results へ落ちるため、
    // ここに導線が無いと「5件目以降は誰も回答できない」という詰みになる。
    expect(
      resolveSurveyDisplayMode({
        status: 'PUBLISHED',
        canViewResults: true,
        resultsWithheldForPrivacy: isResultWithheldForAnonymityPrivacy({
          isAnonymous: true,
          resultsVisibility: 'ALL_MEMBERS',
          responseCount: 5,
        }),
        hasResponded: false,
        allowMultipleSubmissions: false,
        responseRequested: false,
      }),
    ).toBe('results')
    expect(cta({ hasResponded: false })).toBe(true)
  })

  it('回答済み＋複数回答不可 → 回答導線は出ない', () => {
    expect(cta({ hasResponded: true, allowMultipleSubmissions: false })).toBe(false)
  })

  it('回答済み＋複数回答可 → 回答導線が出る（回答を修正できる）', () => {
    expect(cta({ hasResponded: true, allowMultipleSubmissions: true })).toBe(true)
  })

  it('CLOSED では回答導線が出ない（境界）', () => {
    expect(cta({ status: 'CLOSED', hasResponded: false })).toBe(false)
    expect(cta({ status: 'CLOSED', hasResponded: true, allowMultipleSubmissions: true })).toBe(
      false,
    )
  })

  it('DRAFT では回答導線が出ない', () => {
    expect(cta({ status: 'DRAFT' })).toBe(false)
  })
})
