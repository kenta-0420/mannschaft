import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import OrgTimetablePage from '~/pages/organizations/[slug]/timetable.vue'

// テスト環境の既定ロケールは en のため、文言は en の訳で照合する。
/**
 * CMP-261105-1507 組織の時間割画面: 学期の取得失敗を空状態と区別する。
 *
 *   OTT-01 学期の取得失敗は「学期が定義されていません」ではなく再試行できるエラー表示にする
 *   OTT-02 再試行は初回と同じ取得処理を通し、成功すれば一覧を出す
 */

const listPeriods = vi.fn()
const listTerms = vi.fn()

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-a' }, query: {}, path: '/organizations/org-a/timetable' }))
mockNuxtImport('useTimetableApi', () => () => ({
  listPeriods,
  listTerms,
  createOrgTerm: vi.fn(),
  createTeamTerm: vi.fn(),
  deleteTerm: vi.fn(),
}))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: computed(() => true),
  loadPermissions: vi.fn().mockResolvedValue(undefined),
}))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }))

async function flush() {
  for (let i = 0; i < 6; i++) await Promise.resolve()
  await nextTick()
}

beforeEach(() => {
  listPeriods.mockReset().mockResolvedValue([])
  listTerms.mockReset()
})

describe('pages/organizations/[slug]/timetable.vue — 学期の取得失敗', () => {
  it('OTT-01/02: 取得失敗は再試行できるエラー表示にし、再試行で復旧する', async () => {
    listTerms.mockRejectedValueOnce(new Error('network'))
    const wrapper = await mountSuspended(OrgTimetablePage)
    await flush()
    await wrapper.find('[data-testid="org-timetable-tab-term"]').trigger('click')
    await flush()
    expect(wrapper.find('[data-testid="term-load-error-state"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('No terms defined')

    listTerms.mockResolvedValueOnce([
      { id: 21, name: '前期', startDate: '2026-04-01', endDate: '2026-09-30', academicYear: 2026, scopeType: 'ORGANIZATION' },
    ])
    await wrapper.find('[data-testid="term-load-error-state-retry"]').trigger('click')
    await flush()
    expect(wrapper.find('[data-testid="term-load-error-state"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('前期')
  })
})
