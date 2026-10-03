import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import WidgetSurveyResults from '~/components/widgets/WidgetSurveyResults.vue'

const mockGetSurveys = vi.fn()
const mockGetResults = vi.fn()

mockNuxtImport('useSurveyApi', () => () => ({ getSurveys: mockGetSurveys, getResults: mockGetResults }))
mockNuxtImport('useErrorReport', () => () => ({ captureQuiet: vi.fn() }))

beforeEach(() => {
  mockGetSurveys.mockReset()
  mockGetResults.mockReset()
  mockGetSurveys.mockResolvedValue({
    data: [{ id: 1, status: 'PUBLISHED', content: { title: '週次アンケート' }, stats: { responseCount: 2, targetCount: 3 } }],
  })
  mockGetResults.mockResolvedValue({
    data: {
      surveyId: 1,
      title: '週次アンケート',
      responseCount: 2,
      targetCount: 3,
      questionResults: [{ questionId: 10, questionText: '感想', questionType: 'TEXT', totalResponses: 2, optionResults: [], textResponses: ['良い'] }],
    },
  })
})

describe('WidgetSurveyResults.vue', () => {
  it('object形式の結果から設問カードを描画する', async () => {
    const wrapper = await mountSuspended(WidgetSurveyResults, {
      props: { scopeType: 'team', scopeId: '1' },
      global: { stubs: { SurveyQuestionChart: { template: '<div />' } } },
    })
    await new Promise((resolve) => setTimeout(resolve, 0))

    await wrapper.find('[data-testid="widget-survey-toggle-1"]').trigger('click')
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(mockGetResults).toHaveBeenCalledWith(1)
    expect(wrapper.find('[data-testid="widget-result-question-10"]').exists()).toBe(true)
  })
})
