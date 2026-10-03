import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import SurveyTeamBreakdownPanel from '~/components/survey/SurveyTeamBreakdownPanel.vue'

/**
 * F05.4 (B) SurveyTeamBreakdownPanel.vue ユニットテスト
 *
 * 観点:
 *   STB-001: byTeam を描画し、teamId=null は「組織直接メンバー」ラベルになる
 *   STB-002: masked=true のチームは内訳を隠してマスク文言を表示する
 *   STB-003: 403 は握りつぶさず「権限がありません」を表示する
 */
const mockGetTeamBreakdown = vi.fn()

mockNuxtImport('useSurveyApi', () => () => ({
  getTeamBreakdown: mockGetTeamBreakdown,
}))

beforeEach(() => {
  mockGetTeamBreakdown.mockReset()
})

describe('SurveyTeamBreakdownPanel.vue', () => {
  it('STB-001: byTeam を描画し teamId=null は組織直接メンバー枠になる', async () => {
    mockGetTeamBreakdown.mockResolvedValue({
      data: {
        surveyId: 1,
        title: 't',
        total: { respondentCount: 10, questionResults: [] },
        byTeam: [
          {
            teamId: 5,
            teamName: 'A チーム',
            respondentCount: 6,
            masked: false,
            questionResults: [
              {
                questionId: 1,
                questionText: 'Q1',
                questionType: 'SINGLE_CHOICE',
                optionResults: [{ optionId: 1, optionText: 'はい', count: 4, percentage: 66 }],
              },
            ],
          },
          { teamId: null, teamName: null, respondentCount: 5, masked: false, questionResults: [] },
        ],
      },
    })

    const wrapper = await mountSuspended(SurveyTeamBreakdownPanel, { props: { surveyId: 1 } })
    await new Promise((r) => setTimeout(r, 0))

    const html = wrapper.html()
    expect(wrapper.find('[data-testid="survey-team-breakdown-team-5"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="survey-team-breakdown-team-direct"]').exists()).toBe(true)
    expect(html).toContain('A チーム')
  })

  it('STB-002: masked=true のチームは内訳を隠す', async () => {
    mockGetTeamBreakdown.mockResolvedValue({
      data: {
        surveyId: 1,
        title: 't',
        total: { respondentCount: 3, questionResults: [] },
        byTeam: [{ teamId: 9, teamName: 'B', respondentCount: 3, masked: true, questionResults: [] }],
      },
    })

    const wrapper = await mountSuspended(SurveyTeamBreakdownPanel, { props: { surveyId: 1 } })
    await new Promise((r) => setTimeout(r, 0))

    expect(wrapper.find('[data-testid="survey-team-breakdown-masked-9"]').exists()).toBe(true)
  })

  it('STB-003: 403 は forbidden 表示になる', async () => {
    mockGetTeamBreakdown.mockRejectedValue({ statusCode: 403 })

    const wrapper = await mountSuspended(SurveyTeamBreakdownPanel, { props: { surveyId: 1 } })
    await new Promise((r) => setTimeout(r, 0))

    expect(wrapper.find('[data-testid="survey-team-breakdown-forbidden"]').exists()).toBe(true)
  })
})
