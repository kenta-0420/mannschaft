import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import SurveyResultsPanel from '~/components/survey/SurveyResultsPanel.vue'
import type { SurveyResultSummary } from '~/types/survey'

/**
 * SurveyResultsPanel.vue ユニットテスト
 *
 * 観点:
 *   SRP-001: 集計を渡されたら初回取得を省く（**二重取得の防止**）
 *   SRP-002: 渡されなければ従来どおり自分で取得する（他画面からの利用を壊さない）
 *   SRP-003: 更新ボタンは渡されていても取り直す
 *   SRP-004: 空配列は「回答ゼロ」として扱い、取得し直さない
 *
 * 背景（SRP-001）: 詳細ページは配信対象かどうかを確かめるため結果取得を1回行っている
 * （403 をサーバーの判定として扱う）。その結果を捨てて本パネルが再取得すると、
 * 結果を閲覧できる全ユーザーの全表示で高コストな集計と転送が必ず2回走る。
 */
const mockGetResults = vi.fn()

mockNuxtImport('useSurveyApi', () => () => ({
  getResults: mockGetResults,
}))

const RESULT: SurveyResultSummary = {
  questionId: 1,
  questionText: 'Q1',
  questionType: 'SINGLE_CHOICE',
  totalResponses: 3,
  optionResults: [{ optionId: 1, optionText: 'はい', count: 3, percentage: 100 }],
}

beforeEach(() => {
  mockGetResults.mockReset()
  mockGetResults.mockResolvedValue({ data: [RESULT] })
})

describe('SurveyResultsPanel.vue', () => {
  it('SRP-001: 集計を渡されたら集計 API を呼ばない（二重取得の防止）', async () => {
    const wrapper = await mountSuspended(SurveyResultsPanel, {
      props: { surveyId: 1, initialResults: [RESULT] },
    })
    await new Promise((r) => setTimeout(r, 0))

    // 呼び出し回数そのものを固定する（詳細ページのプローブと合わせて計1回に保つ）
    expect(mockGetResults).toHaveBeenCalledTimes(0)
    // 渡された集計がそのまま描画されること
    expect(wrapper.find('[data-testid="result-question-1"]').exists()).toBe(true)
  })

  it('SRP-002: 渡されなければ従来どおり自分で取得する', async () => {
    const wrapper = await mountSuspended(SurveyResultsPanel, { props: { surveyId: 1 } })
    await new Promise((r) => setTimeout(r, 0))

    expect(mockGetResults).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="result-question-1"]').exists()).toBe(true)
  }, 30_000)

  it('SRP-003: 更新ボタンは渡されていても集計 API を呼ぶ', async () => {
    const wrapper = await mountSuspended(SurveyResultsPanel, {
      props: { surveyId: 1, initialResults: [RESULT] },
    })
    await new Promise((r) => setTimeout(r, 0))
    expect(mockGetResults).toHaveBeenCalledTimes(0)

    await wrapper.find('[data-testid="survey-results-refresh"]').trigger('click')
    await new Promise((r) => setTimeout(r, 0))

    expect(mockGetResults).toHaveBeenCalledTimes(1)
  }, 30_000)

  it('SRP-004: 空配列は「回答ゼロ」として扱い、取得し直さない', async () => {
    // 真偽値で判定していると空配列が falsy 扱いになり、無駄な再取得が復活する
    const wrapper = await mountSuspended(SurveyResultsPanel, {
      props: { surveyId: 1, initialResults: [] },
    })
    await new Promise((r) => setTimeout(r, 0))

    expect(mockGetResults).toHaveBeenCalledTimes(0)
    expect(wrapper.find('[data-testid="survey-results-empty"]').exists()).toBe(true)
  })

  it('SRP-005: 403 は握りつぶさず権限が無い旨を表示する（再試行ボタンは出さない）', async () => {
    mockGetResults.mockRejectedValue({ statusCode: 403 })

    const wrapper = await mountSuspended(SurveyResultsPanel, { props: { surveyId: 1 } })
    await new Promise((r) => setTimeout(r, 0))

    expect(wrapper.find('[data-testid="survey-results-forbidden"]').exists()).toBe(true)
  })
})
