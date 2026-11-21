// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive, ref } from 'vue'
import Page from './board.vue'
import ShiftMemberPool from '~/components/shift/ShiftMemberPool.vue'
import DashboardErrorState from '~/components/DashboardErrorState.vue'

// ページ・メンバー欄・取得失敗表示は実物。シフト表／メンバー取得の通信境界だけ差し替える。
const mocks = vi.hoisted(() => ({
  getShiftSchedule: vi.fn(),
  getPositions: vi.fn(),
  getMembers: vi.fn(),
  loadPermissions: vi.fn(),
  loadSlots: vi.fn(),
  fetchRuns: vi.fn(),
  captureQuiet: vi.fn(),
  showError: vi.fn(),
  handleApiError: vi.fn(),
}))
const params = reactive({ slug: 'team-a', scheduleId: '425' })
mockNuxtImport('useRoute', () => () => ({ params }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useShiftApi', () => () => ({
  getShiftSchedule: mocks.getShiftSchedule,
  getPositions: mocks.getPositions,
}))
mockNuxtImport('useTeamApi', () => () => ({ getMembers: mocks.getMembers }))
mockNuxtImport('useRoleAccess', () => () => ({ roleName: ref('MEMBER'), loadPermissions: mocks.loadPermissions }))
mockNuxtImport('useFeatureFlag', () => () => ({ enabled: ref(false) }))
mockNuxtImport('useAutoAssign', () => () => ({
  runs: ref([]),
  isRunning: ref(false),
  runAutoAssign: vi.fn(),
  confirmAutoAssign: vi.fn(),
  revokeAutoAssign: vi.fn(),
  fetchRuns: mocks.fetchRuns,
}))
mockNuxtImport('useShiftBoard', () => () => ({
  localAssignments: ref({}),
  loadSlots: mocks.loadSlots,
  moveUser: vi.fn(),
  addUser: vi.fn(),
  removeUser: vi.fn(),
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))
mockNuxtImport('useNotification', () => () => ({ showError: mocks.showError }))
mockNuxtImport('useErrorReport', () => () => ({ captureQuiet: mocks.captureQuiet }))

function httpError(status: number): Error & { statusCode: number } {
  return Object.assign(new Error(`HTTP ${status}`), { statusCode: status })
}

let wrapper: VueWrapper | undefined
function renderPage(): VueWrapper {
  wrapper = mount(Page, {
    global: {
      components: { ShiftMemberPool, DashboardErrorState },
      mocks: { $t: (key: string) => key },
      directives: { tooltip: {} },
      stubs: {
        PageLoading: { template: '<div data-testid="page-loading" />' },
        Button: {
          props: ['label'],
          emits: ['click'],
          template: '<button v-bind="$attrs" @click="$emit(\'click\')">{{ label }}</button>',
        },
        NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
        ShiftBoardMatrix: true,
        ShiftPdfExportButton: true,
        ShiftAutoAssignModal: true,
        ShiftAutoAssignRunHistory: true,
        Dialog: true,
        Tag: true,
        Avatar: true,
        InputText: true,
        DashboardEmptyState: { props: ['message'], template: '<p>{{ message }}</p>' },
      },
    },
  })
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.getShiftSchedule.mockResolvedValue({
    data: { id: 425, teamId: 9, content: { title: '11月シフト' }, status: { status: 'DRAFT' } },
  })
  mocks.getPositions.mockResolvedValue({ data: [] })
  mocks.getMembers.mockResolvedValue({ data: [{ userId: 2, displayName: 'メンバーB', avatarUrl: null }] })
  mocks.loadPermissions.mockResolvedValue(undefined)
  mocks.loadSlots.mockResolvedValue(undefined)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

describe('シフトボード: シフト表の取得失敗（所属外の直打ち）', () => {
  it('シフト表取得が 404 のとき、メンバー欄のスピナーが残らず、取得失敗の表示が出る', async () => {
    mocks.getShiftSchedule.mockRejectedValue(httpError(404))
    renderPage()
    await flushPromises()

    // 読み込み中のまま残らない（ページ・メンバー欄どちらのスピナーも無い）
    expect(wrapper!.find('[data-testid="page-loading"]').exists()).toBe(false)
    // ボード本体（メンバー欄）は描画しない
    expect(wrapper!.findComponent(ShiftMemberPool).exists()).toBe(false)
    expect(mocks.getMembers).not.toHaveBeenCalled()
    // 取得失敗の表示と戻る導線が出る。404 は再試行を出さない
    const errorState = wrapper!.get('[data-testid="load-error-state"]')
    expect(errorState.text()).toContain('loadErrorState.states.notFoundOrForbidden.title')
    expect(wrapper!.find('[data-testid="load-error-state-retry"]').exists()).toBe(false)
    // 戻る導線はヘッダーの1つだけ（取得失敗表示内に二重に出さない）
    const backLinks = wrapper!.findAll('a[href="/teams/team-a/shifts"]')
    expect(backLinks).toHaveLength(1)
    expect(wrapper!.find('[data-testid="shift-board-load-error"] a').exists()).toBe(false)
    // 操作群（変更依頼・PDF 等）は出さない
    expect(wrapper!.text()).not.toContain('shift.changeRequest.title')
    // 握りつぶさず記録する
    expect(mocks.captureQuiet).toHaveBeenCalledWith(expect.objectContaining({ statusCode: 404 }), expect.anything())
  })

  it('403 でも 404 と同じ表示にし、シフト表の実在を示唆しない', async () => {
    mocks.getShiftSchedule.mockRejectedValue(httpError(404))
    renderPage()
    await flushPromises()
    const notFoundText = wrapper!.get('[data-testid="shift-board-load-error"]').text()
    wrapper!.unmount()

    mocks.getShiftSchedule.mockRejectedValue(httpError(403))
    renderPage()
    await flushPromises()
    expect(wrapper!.get('[data-testid="shift-board-load-error"]').text()).toBe(notFoundText)
    expect(wrapper!.find('[data-testid="page-loading"]').exists()).toBe(false)
  })

  it('通信・サーバー障害では再試行を出し、再試行で成功すればボードを描画する', async () => {
    mocks.getShiftSchedule.mockRejectedValueOnce(httpError(500))
    renderPage()
    await flushPromises()
    expect(wrapper!.find('[data-testid="page-loading"]').exists()).toBe(false)

    await wrapper!.get('[data-testid="load-error-state-retry"]').trigger('click')
    await flushPromises()

    expect(wrapper!.find('[data-testid="shift-board-load-error"]').exists()).toBe(false)
    expect(wrapper!.findComponent(ShiftMemberPool).exists()).toBe(true)
    expect(wrapper!.find('[data-testid="page-loading"]').exists()).toBe(false)
    expect(wrapper!.text()).toContain('メンバーB')
  })
})

describe('シフトボード: 戻る導線', () => {
  it('正常表示でも戻る導線は一覧への1つだけ', async () => {
    renderPage()
    await flushPromises()
    expect(wrapper!.findAll('a[href="/teams/team-a/shifts"]')).toHaveLength(1)
    expect(wrapper!.findAll('.pi-arrow-left')).toHaveLength(1)
  })
})
