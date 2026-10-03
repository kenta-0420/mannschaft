import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import AttendanceTeamBreakdownPanel from '~/components/schedule/AttendanceTeamBreakdownPanel.vue'

/**
 * F03.1 (B) AttendanceTeamBreakdownPanel.vue ユニットテスト
 *
 * 観点:
 *   ATB-001: byTeam の行と total フッターを描画し、teamId=null は組織直接メンバー枠になる
 *   ATB-002: 403 は握りつぶさず forbidden 表示になる
 *
 * 注: 当コンポーネントは useScheduleAttendance を明示 import するため（nuxt の auto-import 対象外）、
 *     vi.mock でモジュールパスを差し替える。
 */
const mockGetBreakdown = vi.fn()
const mockExportCsv = vi.fn()

vi.mock('~/composables/schedule/useScheduleAttendance', () => ({
  useScheduleAttendance: () => ({
    getAttendanceTeamBreakdown: mockGetBreakdown,
    exportAttendanceTeamBreakdownCsv: mockExportCsv,
  }),
}))

mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn() }))

beforeEach(() => {
  mockGetBreakdown.mockReset()
  mockExportCsv.mockReset()
})

describe('AttendanceTeamBreakdownPanel.vue', () => {
  it('ATB-001: byTeam 行と total フッターを描画する', async () => {
    mockGetBreakdown.mockResolvedValue({
      data: {
        scheduleId: 123,
        total: { attending: 8, partial: 1, absent: 2, undecided: 3 },
        byTeam: [
          { teamId: 5, teamName: 'A チーム', attending: 4, partial: 0, absent: 1, undecided: 1 },
          { teamId: null, teamName: null, attending: 2, partial: 1, absent: 0, undecided: 2 },
        ],
      },
    })

    const wrapper = await mountSuspended(AttendanceTeamBreakdownPanel, {
      props: { orgPublicId: 'org-uuid', scheduleId: 123 },
    })
    await new Promise((r) => setTimeout(r, 0))

    expect(wrapper.find('[data-testid="attendance-team-breakdown-row-5"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="attendance-team-breakdown-row-direct"]').exists()).toBe(true)
    expect(wrapper.html()).toContain('A チーム')
  })

  it('ATB-002: 403 は forbidden 表示になる', async () => {
    mockGetBreakdown.mockRejectedValue({ statusCode: 403 })

    const wrapper = await mountSuspended(AttendanceTeamBreakdownPanel, {
      props: { orgPublicId: 'org-uuid', scheduleId: 123 },
    })
    await new Promise((r) => setTimeout(r, 0))

    expect(wrapper.find('[data-testid="attendance-team-breakdown-forbidden"]').exists()).toBe(true)
  })
})
