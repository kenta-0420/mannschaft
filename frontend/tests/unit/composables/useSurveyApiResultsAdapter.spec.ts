import { describe, expect, it } from 'vitest'
import { adaptSurveyResults } from '~/composables/useSurveyApi'

describe('adaptSurveyResults', () => {
  it('実wire objectを設問単位のFE結果へ正規化し、回答数はルートのresponseCountを使う', () => {
    const result = adaptSurveyResults({
      surveyId: 12,
      title: '満足度',
      responseCount: 4,
      targetCount: 10,
      questionResults: [
        { questionId: 1, questionText: '自由記述', questionType: 'FREE_TEXT', textResponses: ['良い'] },
        {
          questionId: 2,
          questionText: '評価',
          questionType: 'SCALE',
          optionResults: [{ optionId: 5, optionText: '5', count: 4, percentage: 100 }],
        },
      ],
    })

    expect(result).toMatchObject({ surveyId: 12, title: '満足度', responseCount: 4, targetCount: 10 })
    expect(result.questionResults).toEqual([
      expect.objectContaining({ questionId: 1, questionType: 'TEXT', totalResponses: 4, textResponses: ['良い'] }),
      expect.objectContaining({ questionId: 2, questionType: 'RATING', totalResponses: 4 }),
    ])
  })

  it('questionResultsが空なら空配列を保持する', () => {
    expect(adaptSurveyResults({ responseCount: 0, questionResults: [] }).questionResults).toEqual([])
  })
})
