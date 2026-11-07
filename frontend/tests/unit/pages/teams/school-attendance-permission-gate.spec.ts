import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import dayjs from 'dayjs'
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
const loadCandidates = vi.fn()
const loadMonthlyStatistics = vi.fn()
const loadTermStatistics = vi.fn()
const downloadCsv = vi.fn()
const loadAlerts = vi.fn()

vi.mock('~/composables/useAttendancePermissionsApi', () => ({
  useAttendancePermissionsApi: () => ({ getPermissions: mockGetPermissions }),
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError, handleError: handleApiError }))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 't1' } }))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))
mockNuxtImport('useDailyRollCall', () => () => ({
  records: ref([]),
  loading: ref(false),
  submitting: ref(false),
  lastSummary: ref(null),
  forbidden: ref(false),
  loadRecords,
  submitRollCall: vi.fn(),
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
  alerts: ref([]),
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
  })
})
