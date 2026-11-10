import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import dayjs from 'dayjs'
import type { DailyAttendanceResponse, TransitionAlertResponse } from '~/types/school'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'

dayjs.extend(utc)
dayjs.extend(timezone)

/**
 * CMP-261001-0630 検分1巡目 P2:
 *   - 権限照会が「照会中 / 拒否 / 取得失敗」のあいだは、学校出欠の教員用4画面から一覧 API を呼ばない
 *   - 取得失敗は「権限がありません」ではなく、再試行可能なエラー状態として区別して出す
 *   - 照会が成功し該当権限が true のときだけ一覧を取得する（対照）
 */
const mockGetPermissions = vi.fn()
const handleApiError = vi.fn()

const loadRecords = vi.fn()
const submitRollCall = vi.fn()
const dailyRecords = ref<DailyAttendanceResponse[]>([])
const dailyFailed = ref(false)
const dailyLoaded = ref(false)
const dailyKey = ref('')
const loadTeamNotices = vi.fn()
const acknowledgeNotice = vi.fn()
const applyNotice = vi.fn()
const loadCandidates = vi.fn()
const loadMonthlyStatistics = vi.fn()
const loadTermStatistics = vi.fn()
const downloadCsv = vi.fn()
const loadAlerts = vi.fn()
const transitionAlerts = ref<TransitionAlertResponse[]>([])

vi.mock('~/composables/useAttendancePermissionsApi', () => ({
  useAttendancePermissionsApi: () => ({ getPermissions: mockGetPermissions }),
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError, handleError: handleApiError }))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'class-blue' } }))
mockNuxtImport('useSchoolTeamContext', () => () => ({
  teamId: ref('42'), loading: ref(false), error: ref(undefined), load: async () => true,
}))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))
mockNuxtImport('useDailyRollCall', () => () => ({
  records: dailyRecords,
  loadFailed: dailyFailed,
  loaded: dailyLoaded,
  recordKey: dailyKey,
  loading: ref(false),
  submitting: ref(false),
  lastSummary: ref(null),
  forbidden: ref(false),
  loadRecords,
  submitRollCall,
}))
mockNuxtImport('useFamilyAttendanceNotice', () => () => ({
  noticeList: ref(null), loading: ref(false), processing: ref(false),
  loadTeamNotices, acknowledge: acknowledgeNotice, apply: applyNotice,
}))
mockNuxtImport('usePeriodAttendance', () => () => ({
  candidates: ref([]),
  loading: ref(false),
  submitting: ref(false),
  lastSummary: ref(null),
  forbidden: ref(false),
  loadCandidates,
  submitPeriodAttendance: vi.fn(),
}))
mockNuxtImport('useAttendanceStatistics', () => () => ({
  monthlyStats: ref(null),
  termStats: ref(null),
  loadingMonthly: ref(false),
  loadingTerm: ref(false),
  exporting: ref(false),
  loadMonthlyStatistics,
  loadTermStatistics,
  downloadCsv,
}))
mockNuxtImport('useTransitionAlert', () => () => ({
  alerts: transitionAlerts,
  loading: ref(false),
  unresolvedCount: ref(0),
  totalCount: ref(0),
  loadAlerts,
}))

const stubs = {
  DailyRollCallSheet: true,
  PeriodAttendanceSheet: true,
  MonthlyAttendanceStatsChart: true,
  TransitionAlertBanner: true,
  BackButton: true,
  PageLoading: true,
}

const ALL_TRUE = { teamId: 1, canView: true, canRecordDaily: true, canRecordPeriod: true }
const ALL_FALSE = { teamId: 1, canView: false, canRecordDaily: false, canRecordPeriod: false }

async function flush(): Promise<void> {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
  }
}

type Wrapper = Awaited<ReturnType<typeof mountSuspended>>
type PageCase = {
  name: string
  load: () => Promise<{ default: object }>
  listMock: ReturnType<typeof vi.fn>
  /** 利用者が一覧取得につながる操作を行う（権限未確定でも発火しうる経路をすべて叩く） */
  act: (w: Wrapper) => Promise<void>
}

const pages: PageCase[] = [
  {
    name: '保護者連絡',
    load: () => import('~/pages/teams/[slug]/school-attendance/notices.vue'),
    listMock: loadTeamNotices,
    act: async (w) => { await (w.vm as unknown as { onDateChange: () => Promise<void> }).onDateChange() },
  },
  {
    name: '日次出欠',
    load: () => import('~/pages/teams/[slug]/school-attendance/daily-roll-call.vue'),
    listMock: loadRecords,
    act: async (w) => {
      ;(w.vm as unknown as { selectedDate: string }).selectedDate = '2026-01-02'
      const input = w.find('[data-testid="daily-roll-call-date"]')
      if (input.exists()) await input.trigger('change')
    },
  },
  {
    name: '時限出欠',
    load: () => import('~/pages/teams/[slug]/school-attendance/period-attendance.vue'),
    listMock: loadCandidates,
    act: async (w) => {
      const vm = w.vm as unknown as { selectedDate: string; selectedPeriod: number }
      vm.selectedDate = '2026-01-02'
      vm.selectedPeriod = 3
    },
  },
  {
    name: '統計',
    load: () => import('~/pages/teams/[slug]/school-attendance/statistics.vue'),
    listMock: loadMonthlyStatistics,
    act: async (w) => {
      const vm = w.vm as unknown as { selectedYear: number; loadMonthly: () => Promise<void> }
      vm.selectedYear = 2025
      await vm.loadMonthly()
    },
  },
  {
    name: 'アラート',
    load: () => import('~/pages/teams/[slug]/school-attendance/transition-alerts.vue'),
    listMock: loadAlerts,
    act: async (w) => {
      const vm = w.vm as unknown as { onFilterChange: () => Promise<void>; onDateChange: () => Promise<void> }
      await vm.onFilterChange()
      await vm.onDateChange()
    },
  },
]

beforeEach(() => {
  dailyRecords.value = []
  transitionAlerts.value = []
  dailyFailed.value = false
  dailyLoaded.value = false
  dailyKey.value = ''
  submitRollCall.mockReset()
  loadTeamNotices.mockReset()
  acknowledgeNotice.mockReset()
  applyNotice.mockReset()
  mockGetPermissions.mockReset()
  handleApiError.mockReset()
  for (const m of [loadRecords, loadCandidates, loadMonthlyStatistics, loadTermStatistics, downloadCsv, loadAlerts]) {
    m.mockReset()
  }
})

describe.each(pages)('学校出欠 $name 画面の権限ゲート', (p) => {
  it('照会中は操作しても一覧 API を呼ばない', async () => {
    mockGetPermissions.mockReturnValue(new Promise(() => {}))
    const page = (await p.load()).default
    const w = await mountSuspended(page, { global: { stubs } })
    await flush()
    await p.act(w)
    await flush()
    expect(p.listMock).not.toHaveBeenCalled()
  })

  it('拒否（全権限 false）では一覧 API を呼ばず「権限がありません」を出す', async () => {
    mockGetPermissions.mockResolvedValue(ALL_FALSE)
    const page = (await p.load()).default
    const w = await mountSuspended(page, { global: { stubs } })
    await flush()
    await p.act(w)
    await flush()
    expect(p.listMock).not.toHaveBeenCalled()
    expect(w.find('[data-testid="school-attendance-forbidden"]').exists()).toBe(true)
    expect(w.find('[data-testid="school-attendance-permission-error"]').exists()).toBe(false)
  })

  it('取得失敗では一覧 API を呼ばず、拒否と区別した再試行可能なエラーを出す', async () => {
    mockGetPermissions.mockRejectedValue({ statusCode: 500 })
    const page = (await p.load()).default
    const w = await mountSuspended(page, { global: { stubs } })
    await flush()
    await p.act(w)
    await flush()
    expect(p.listMock).not.toHaveBeenCalled()
    expect(w.find('[data-testid="school-attendance-permission-error"]').exists()).toBe(true)
    expect(w.find('[data-testid="school-attendance-forbidden"]').exists()).toBe(false)
    expect(w.find('[data-testid="school-attendance-permission-error-retry"]').exists()).toBe(true)
    expect(handleApiError).toHaveBeenCalledTimes(1)
  })

  it('取得失敗から再試行で復旧すると一覧を取得する', async () => {
    mockGetPermissions.mockRejectedValueOnce({ statusCode: 500 }).mockResolvedValue(ALL_TRUE)
    const page = (await p.load()).default
    const w = await mountSuspended(page, { global: { stubs } })
    await flush()
    expect(p.listMock).not.toHaveBeenCalled()
    await w.find('[data-testid="school-attendance-permission-error-retry"]').trigger('click')
    await flush()
    expect(w.find('[data-testid="school-attendance-permission-error"]').exists()).toBe(false)
    expect(p.listMock).toHaveBeenCalled()
  })

  it('（対照）照会成功かつ権限 true なら一覧を取得する', async () => {
    mockGetPermissions.mockResolvedValue(ALL_TRUE)
    const page = (await p.load()).default
    await mountSuspended(page, { global: { stubs } })
    await flush()
    expect(p.listMock).toHaveBeenCalled()
    expect(mockGetPermissions).toHaveBeenCalledWith('42')
  })
})


describe('点呼の提出表示', () => {
  it('閲覧Vのみでは提出ボタンを表示せず、handlerからも提出しない', async () => {
    mockGetPermissions.mockResolvedValue({ ...ALL_TRUE, canRecordDaily: false })
    const page = (await import('~/pages/teams/[slug]/school-attendance/daily-roll-call.vue')).default
    const wrapper = await mountSuspended(page, { global: { stubs } })
    await flush()
    expect(wrapper.find('[data-testid="daily-roll-call-submit"]').exists()).toBe(false)
    const vm = wrapper.vm as unknown as { onSubmit: () => Promise<void>; canSubmit: boolean }
    expect(vm.canSubmit).toBe(false)
    await vm.onSubmit()
    expect(submitRollCall).not.toHaveBeenCalled()
  })
})


describe('点呼一覧の失敗と空の成功', () => {
  it('500はシートと提出を隠し、同じ取得の再試行で回復する', async () => {
    mockGetPermissions.mockResolvedValue(ALL_TRUE)
    loadRecords.mockImplementationOnce(async () => { dailyFailed.value = true })
      .mockImplementationOnce(async (date: string) => {
        dailyFailed.value = false
        dailyLoaded.value = true
        dailyKey.value = `42:${date}`
      })
    const page = (await import('~/pages/teams/[slug]/school-attendance/daily-roll-call.vue')).default
    const wrapper = await mountSuspended(page, { global: { stubs } })
    await flush()
    expect(wrapper.find('[data-testid="daily-roll-call-load-error"]').exists()).toBe(true)
    expect(wrapper.find('daily-roll-call-sheet-stub').exists()).toBe(false)
    expect(wrapper.find('[data-testid="daily-roll-call-submit"]').exists()).toBe(false)
    await wrapper.get('[data-testid="daily-roll-call-load-error-retry"]').trigger('click')
    await flush()
    expect(wrapper.find('[data-testid="daily-roll-call-load-error"]').exists()).toBe(false)
    expect(wrapper.find('daily-roll-call-sheet-stub').exists()).toBe(true)
  })

  it('有件の翌日が成功0件なら古い行を残さない', async () => {
    mockGetPermissions.mockResolvedValue(ALL_TRUE)
    loadRecords.mockImplementationOnce(async (date: string) => {
      dailyRecords.value = [{ studentUserId: 1, status: 'ABSENT' } as DailyAttendanceResponse]
      dailyLoaded.value = true
      dailyKey.value = `42:${date}`
    }).mockImplementationOnce(async (date: string) => {
      dailyRecords.value = []
      dailyKey.value = `42:${date}`
    })
    const page = (await import('~/pages/teams/[slug]/school-attendance/daily-roll-call.vue')).default
    const wrapper = await mountSuspended(page, { global: { stubs } })
    await flush()
    const vm = wrapper.vm as unknown as { entries: unknown[]; selectedDate: string; onDateChange: () => Promise<void> }
    expect(vm.entries).toHaveLength(1)
    vm.selectedDate = '2026-01-02'
    await vm.onDateChange()
    await flush()
    expect(vm.entries).toEqual([])
  })
})


describe('保護者連絡ページの操作資格伝達と二重ガード', () => {
  it.each([false, true])('閲覧Vあり・日次R=%sをleafへ渡しemit経由の操作も同じ条件で抑止する', async canRecordDaily => {
    mockGetPermissions.mockResolvedValue({ ...ALL_TRUE, canRecordDaily })
    const page = (await import('~/pages/teams/[slug]/school-attendance/notices.vue')).default
    const wrapper = await mountSuspended(page, {
      global: { stubs: { ...stubs, TeacherInboxNoticeList: { name: 'TeacherInboxNoticeList', props: ['canRecord'], template: '<div />' } } },
    })
    await flush()
    const child = wrapper.findComponent({ name: 'TeacherInboxNoticeList' })
    expect(child.props('canRecord')).toBe(canRecordDaily)
    child.vm.$emit('acknowledge', 7)
    child.vm.$emit('apply', 7)
    await flush()
    expect(acknowledgeNotice).toHaveBeenCalledTimes(canRecordDaily ? 1 : 0)
    expect(applyNotice).toHaveBeenCalledTimes(canRecordDaily ? 1 : 0)
  })
})


it.each([false, true])('アラートページは日次R=%sを解決操作へ渡す', async canRecordDaily => {
  transitionAlerts.value = [{ id: 7, teamId: 42, resolved: false } as TransitionAlertResponse]
  mockGetPermissions.mockResolvedValue({ ...ALL_TRUE, canRecordDaily })
  const page = (await import('~/pages/teams/[slug]/school-attendance/transition-alerts.vue')).default
  const wrapper = await mountSuspended(page, {
    global: { stubs: { ...stubs, TransitionAlertBanner: { name: 'TransitionAlertBanner', props: ['canResolve'], template: '<div />' } } },
  })
  await flush()
  expect(wrapper.findComponent({ name: 'TransitionAlertBanner' }).props('canResolve')).toBe(canRecordDaily)
})
