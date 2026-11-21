import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import ShiftIndexPage from '~/pages/shift/index.vue'
import ShiftDetailPage from '~/pages/shift/[id]/index.vue'
import ShiftEditPage from '~/pages/shift/[id]/edit.vue'
import ShiftRequestsPage from '~/pages/shift/[id]/requests.vue'
import type { ShiftScheduleResponse, ShiftSlotResponse } from '~/types/shift'

const getSchedule = vi.fn()
const listSchedules = vi.fn()
const listSlots = vi.fn()
const listRequests = vi.fn()
const getRequestSummary = vi.fn()
const getAllMembers = vi.fn()
const handleApiError = vi.fn()
let role = 'MEMBER'
let slug: string | undefined = 'alpha'

vi.mock('~/composables/team/useTeamMembers', () => ({
  useTeamMembers: () => ({ getAllMembers }),
}))

const teamStore = {
  get myTeams() { return [{ id: 1, role, name: 'Alpha', ...(slug ? { slug } : {}) }] },
  fetchMyTeams: vi.fn(async () => {}),
}

mockNuxtImport('useRoute', () => () => ({ params: { id: '91' }, path: '/shift/91' }))
mockNuxtImport('useTeamStore', () => () => teamStore)
mockNuxtImport('useShiftApi', () => () => ({ getSchedule, listSchedules }))
mockNuxtImport('useShiftSlotApi', () => () => ({ listSlots, deleteSlot: vi.fn(), bulkCreateSlots: vi.fn() }))
mockNuxtImport('useShiftRequestApi', () => () => ({ listRequests, getRequestSummary }))
mockNuxtImport('useShiftPositionApi', () => () => ({ listPositions: vi.fn(async () => []) }))
mockNuxtImport('useDatetime', () => () => ({
  userTimezone: ref('Asia/Tokyo'),
  formatDate: (iso: string) => iso.replace(/-/g, '/'),
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), warn: vi.fn() }))
mockNuxtImport('navigateTo', () => vi.fn())

const schedule: ShiftScheduleResponse = {
  id: 91, teamId: 1,
  content: { title: '表示確認シフト', periodType: 'WEEKLY', note: null },
  period: { startDate: '2026-10-10', endDate: '2026-10-11', requestDeadline: null },
  status: { status: 'COLLECTING', publishedAt: null, publishedBy: null },
  audit: { createdBy: 3, createdAt: '2026-09-29T00:00:00', updatedAt: '2026-09-29T00:00:00' },
}
const slot: ShiftSlotResponse = {
  version: 0, id: 301, scheduleId: 91,
  time: { slotDate: '2026-10-10', startTime: '09:00:00', endTime: '12:00:00', endsNextDay: false },
  position: { positionId: null, positionName: null, requiredCount: 2 },
  assignedUserIds: [], assignmentMasked: false, note: null,
}

beforeEach(() => {
  vi.clearAllMocks()
  role = 'MEMBER'
  slug = 'alpha'
  getSchedule.mockResolvedValue(schedule)
  listSchedules.mockResolvedValue([schedule])
  listSlots.mockResolvedValue([slot])
  listRequests.mockResolvedValue([
    { userId: 7, slotDate: '2026-10-10', preference: 'PREFERRED' },
    { userId: 8, slotDate: '2026-10-10', preference: 'AVAILABLE' },
  ])
  getRequestSummary.mockResolvedValue({
    totalMembers: 2, submittedCount: 2, pendingCount: 0,
    preferredCount: 1, availableCount: 1, weakRestCount: 0, strongRestCount: 0, absoluteRestCount: 0,
  })
  getAllMembers.mockResolvedValue({ data: [{ userId: 7, displayName: '山田花子' }] })
})

async function mount(c: object) {
  const w = await mountSuspended(c)
  await flushPromises()
  return w
}

describe('S1 変更依頼への導線', () => {
  for (const r of ['MEMBER', 'ADMIN']) {
    it(`${r} にも変更依頼画面へのリンクを出す`, async () => {
      role = r
      const w = await mount(ShiftDetailPage)
      expect(w.find('a[href="/teams/alpha/shifts/91/change-requests"]').exists()).toBe(true)
    })
  }
  it('slug が引けないときは導線を出さない', async () => {
    slug = undefined
    const w = await mount(ShiftDetailPage)
    expect(w.find('a[href*="change-requests"]').exists()).toBe(false)
  })
})

describe('S3 summary は管理者だけ', () => {
  it('会員では GET summary を呼ばない', async () => {
    role = 'MEMBER'
    await mount(ShiftIndexPage)
    expect(listSchedules).toHaveBeenCalled()
    expect(getRequestSummary).not.toHaveBeenCalled()
  })
  it('管理者では呼ぶ', async () => {
    role = 'ADMIN'
    await mount(ShiftIndexPage)
    expect(getRequestSummary).toHaveBeenCalledWith(91)
  })
})

describe('S17 一覧のフィルタ', () => {
  it('日付入力は w-36 固定幅でなく、ステータス Select に aria-label がある', async () => {
    const w = await mount(ShiftIndexPage)
    const dates = w.findAll('input[type="date"]')
    expect(dates.length).toBeGreaterThanOrEqual(2)
    expect(dates[0]!.classes()).not.toContain('w-36')
    expect(w.find('[aria-label="Status"]').exists()).toBe(true)
  })
})

describe('S4 希望一覧の表示名', () => {
  it('UID: を出さず表示名を出し、解決できなければ不明なメンバー', async () => {
    role = 'ADMIN'
    const w = await mount(ShiftRequestsPage)
    expect(w.text()).not.toContain('UID:')
    expect(w.text()).toContain('山田花子')
    expect(w.text()).toContain('Unknown member')
  })
})

describe('S4 失敗系', () => {
  it('members/all が失敗したら通知し、UID: でなく不明メンバー表示', async () => {
    role = 'ADMIN'
    getAllMembers.mockRejectedValue(new Error('boom'))
    const w = await mount(ShiftRequestsPage)
    expect(handleApiError).toHaveBeenCalled()
    expect(w.text()).not.toContain('UID:')
    expect(w.text()).toContain('Unknown member')
  })
  it('希望一覧のタブも 44px 以上', async () => {
    role = 'ADMIN'
    const w = await mount(ShiftRequestsPage)
    for (const a of w.findAll('nav a')) expect(a.classes()).toContain('min-h-11')
  })
})

describe('S5 期間ヘッダーの日付書式', () => {
  it('ISO 文字列のまま出さない', async () => {
    const w = await mount(ShiftDetailPage)
    expect(w.text()).toContain('2026/10/10')
    expect(w.text()).not.toContain('2026-10-10')
  })
})

describe('S7 / S17 編集画面・詳細画面', () => {
  it('割当人数の文言になり、バッジ・タブにクラスが付く', async () => {
    role = 'ADMIN'
    const w = await mount(ShiftEditPage)
    expect(w.text()).toContain('Assigned headcount')
    for (const a of w.findAll('nav a')) expect(a.classes()).toContain('min-h-11')
    expect(w.find('.p-tag').classes()).toContain('whitespace-nowrap')
  })
  it('詳細画面のタブも 44px 以上', async () => {
    const w = await mount(ShiftDetailPage)
    for (const a of w.findAll('nav a')) expect(a.classes()).toContain('min-h-11')
  })
})

describe('locale（6言語）', () => {
  const langs = ['ja', 'en', 'zh', 'ko', 'es', 'de']
  const load = (l: string) =>
    JSON.parse(readFileSync(resolve(__dirname, `../../../../app/locales/${l}/shift.json`), 'utf8'))
  it('新キーが6言語にあり、ja 以外に仮名が無い', () => {
    for (const l of langs) {
      const s = load(l).shift
      for (const v of [s.requests.unknownMember, s.detail.changeRequestLink, s.index.statusFilterLabel]) {
        expect(typeof v).toBe('string')
        if (l !== 'ja') expect(v).not.toMatch(/[぀-ヿ]/)
      }
    }
  })
  it('希望一覧ヘルプは管理者限定と明記される', () => {
    const g = load('ja').shift_guide.requests
    expect(g.body).toContain('管理者')
    expect(g.steps.step1).toContain('管理者')
  })
})
