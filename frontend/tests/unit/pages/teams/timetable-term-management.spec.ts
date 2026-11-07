import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import TeamTimetablePage from '~/pages/teams/[slug]/timetable.vue'

// テスト環境の既定ロケールは en のため、文言は en の訳で照合する。
/**
 * CMP-261105-1507 チームが自分で学期と時間割を管理できること。
 *
 *   TTP-01 時間割作成ダイアログを開くときの学期取得の失敗を握りつぶさず利用者に見せる
 *   TTP-02 チーム画面に学期タブがあり、管理者には学期の追加操作が出る
 *   TTP-03 管理権限が無ければ学期の追加・時間割の削除を出さない
 *   TTP-04 時間割の削除は確認を挟み、承認後にだけ削除 API を呼ぶ
 */

const list = vi.fn()
const listTerms = vi.fn()
const remove = vi.fn()
const notifyError = vi.fn()
const notifySuccess = vi.fn()
let admin = true
let pendingAccept: (() => void | Promise<void>) | null = null
const confirmAction = vi.fn((options: { onAccept: () => void | Promise<void> }) => {
  pendingAccept = options.onAccept
})

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-a' }, query: {}, path: '/teams/team-a/timetable' }))
mockNuxtImport('useTimetableApi', () => () => ({
  list,
  listTerms,
  remove,
  getWeekly: vi.fn().mockResolvedValue(null),
  listChanges: vi.fn().mockResolvedValue([]),
  createTeamTerm: vi.fn(),
  createOrgTerm: vi.fn(),
  deleteTerm: vi.fn(),
}))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: computed(() => admin),
  can: () => false,
  loadPermissions: vi.fn().mockResolvedValue(undefined),
}))
mockNuxtImport('useNotification', () => () => ({
  success: notifySuccess,
  error: notifyError,
  warn: vi.fn(),
  info: vi.fn(),
}))
mockNuxtImport('useConfirmDialog', () => () => ({ confirmAction }))

const timetable = {
  id: 7,
  teamId: 1,
  termId: 11,
  termName: '1学期',
  name: '前期時間割',
  status: 'DRAFT',
  visibility: 'MEMBERS_ONLY',
  effectiveFrom: '2026-04-01',
  effectiveUntil: null,
  weekPatternEnabled: false,
}

async function flush() {
  for (let i = 0; i < 6; i++) await Promise.resolve()
  await nextTick()
}

async function mountPage() {
  const wrapper = await mountSuspended(TeamTimetablePage)
  await flush()
  return wrapper
}

beforeEach(() => {
  admin = true
  list.mockReset().mockResolvedValue([timetable])
  listTerms.mockReset().mockResolvedValue([])
  remove.mockReset().mockResolvedValue(undefined)
  notifyError.mockReset()
  notifySuccess.mockReset()
  confirmAction.mockClear()
  pendingAccept = null
})

describe('pages/teams/[slug]/timetable.vue — チーム単独の学期・時間割管理', () => {
  it('TTP-01: 作成ダイアログを開くときの学期取得失敗を利用者に見せる', async () => {
    const wrapper = await mountPage()
    listTerms.mockRejectedValueOnce(new Error('network'))
    await wrapper.find('[data-testid="timetable-create-button"]').trigger('click')
    await flush()
    expect(notifyError).toHaveBeenCalledWith('Failed to load terms')
  })

  it('TTP-02: 学期タブがあり、管理者には学期の追加操作が出る', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="timetable-tab-term"]').exists()).toBe(true)
    await wrapper.find('[data-testid="timetable-tab-term"]').trigger('click')
    await flush()
    expect(wrapper.find('[data-testid="term-add-button"]').exists()).toBe(true)
  })

  it('TTP-03: 管理権限が無ければ学期の追加・時間割の削除を出さない', async () => {
    admin = false
    const wrapper = await mountPage()
    await wrapper.find('[data-testid="timetable-tab-term"]').trigger('click')
    await flush()
    expect(wrapper.find('[data-testid="term-add-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="timetable-delete-7"]').exists()).toBe(false)
  })

  it('TTP-05: 学期タブの取得失敗は空状態ではなく再試行できるエラー表示にし、再試行で復旧する', async () => {
    const wrapper = await mountPage()
    listTerms.mockRejectedValueOnce(new Error('network'))
    await wrapper.find('[data-testid="timetable-tab-term"]').trigger('click')
    await flush()
    expect(wrapper.find('[data-testid="term-load-error-state"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('No terms defined')

    listTerms.mockResolvedValueOnce([
      { id: 11, name: '1学期', startDate: '2026-04-01', endDate: '2026-07-31', academicYear: 2026, scopeType: 'TEAM' },
    ])
    await wrapper.find('[data-testid="term-load-error-state-retry"]').trigger('click')
    await flush()
    expect(wrapper.find('[data-testid="term-load-error-state"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('1学期')
  })

  it('TTP-04: 時間割の削除は確認を挟み、承認後にだけ削除 API を呼ぶ', async () => {
    const wrapper = await mountPage()
    await wrapper.find('[data-testid="timetable-tab-list"]').trigger('click')
    await flush()
    await wrapper.find('[data-testid="timetable-delete-7"]').trigger('click')
    expect(confirmAction).toHaveBeenCalledTimes(1)
    expect(remove).not.toHaveBeenCalled()
    await pendingAccept!()
    await flush()
    expect(remove).toHaveBeenCalledWith('team-a', 7)
    expect(notifySuccess).toHaveBeenCalledWith('Timetable deleted')
  })
})
