// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref, shallowRef } from 'vue'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
import { useMemberCardManagement } from './useMemberCardManagement'
dayjs.extend(utc)
dayjs.extend(timezone)

const mocks = vi.hoisted(() => ({
  listByTeam: vi.fn(), listByOrg: vi.fn(), getTeamCheckinStats: vi.fn(), suspend: vi.fn(), reactivate: vi.fn(),
  getTeam: vi.fn(), getOrganization: vi.fn(), loadPermissions: vi.fn(), handleApiError: vi.fn(), success: vi.fn(),
}))

// APIとVueのリアクティビティのみを使うためNuxtアプリを起動しない。
vi.mock('~/composables/useMemberCardApi', () => ({ useMemberCardApi: () => mocks }))
vi.mock('~/composables/useTeamApi', () => ({ useTeamApi: () => mocks }))
vi.mock('~/composables/useOrganizationApi', () => ({ useOrganizationApi: () => mocks }))
vi.mock('~/composables/useRoleAccess', () => ({ useRoleAccess: () => ({ loadPermissions: mocks.loadPermissions, isAdminOrDeputy: ref(true) }) }))
vi.mock('~/composables/useDatetime', () => ({ useDatetime: () => ({ userTimezone: ref('Asia/Tokyo') }) }))
vi.mock('~/composables/useErrorHandler', () => ({ useErrorHandler: () => ({ handleApiError: mocks.handleApiError }) }))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => ({ success: mocks.success }) }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.stubGlobal('ref', ref)
vi.stubGlobal('shallowRef', shallowRef)
vi.stubGlobal('useMemberCardApi', () => mocks)
vi.stubGlobal('useTeamApi', () => mocks)
vi.stubGlobal('useOrganizationApi', () => mocks)
vi.stubGlobal('useRoleAccess', () => ({ loadPermissions: mocks.loadPermissions, isAdminOrDeputy: ref(true) }))
vi.stubGlobal('useDatetime', () => ({ userTimezone: ref('Asia/Tokyo') }))
vi.stubGlobal('useErrorHandler', () => ({ handleApiError: mocks.handleApiError }))
vi.stubGlobal('useNotification', () => ({ success: mocks.success }))
vi.stubGlobal('useI18n', () => ({ t: (key: string) => key }))

describe('useMemberCardManagement', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-31T16:00:00Z'))
    mocks.getTeam.mockResolvedValue({ data: { id: 'team-slug', numericId: 12 } })
    mocks.getOrganization.mockResolvedValue({ data: { id: 'org-slug', numericId: 34 } })
    mocks.loadPermissions.mockResolvedValue({ ok: true })
    mocks.listByTeam.mockImplementation((_id, status) => Promise.resolve([{ id: status, status }]))
    mocks.listByOrg.mockResolvedValue([])
    mocks.getTeamCheckinStats.mockResolvedValue({ totalCheckins: 0 })
  })
  afterEach(() => vi.useRealTimers())

  it('slugから内部IDを解決して3状態を取得し、ユーザーTZの当月期間を送る', async () => {
    const state = useMemberCardManagement('team', ref('team-slug'))
    await state.loadData()
    expect(mocks.getTeam).toHaveBeenCalledWith('team-slug')
    expect(mocks.listByTeam.mock.calls).toEqual([['12', 'ACTIVE'], ['12', 'SUSPENDED'], ['12', 'REVOKED']])
    expect(mocks.getTeamCheckinStats).toHaveBeenCalledWith('12', { from: '2026-11-01', to: '2026-11-01' })
    expect(state.cards.value).toHaveLength(3)
    expect(state.loadError.value).toBeNull()
  })

  it('組織も内部IDを使用し、0件を成功として扱いチーム統計を呼ばない', async () => {
    const state = useMemberCardManagement('organization', ref('org-slug'))
    await state.loadData()
    expect(mocks.listByOrg).toHaveBeenCalledWith('34', 'SUSPENDED')
    expect(state.cards.value).toEqual([])
    expect(state.loadError.value).toBeNull()
    expect(mocks.getTeamCheckinStats).not.toHaveBeenCalled()
  })

  it('一部状態の失敗を空状態にせず、再試行で全取得をやり直す', async () => {
    const error = { statusCode: 503 }
    mocks.listByTeam.mockRejectedValueOnce(error)
    const state = useMemberCardManagement('team', ref('team-slug'))
    await state.loadData()
    expect(state.loadError.value).toBe(error)
    expect(state.loading.value).toBe(false)
    await state.loadData()
    expect(state.loadError.value).toBeNull()
    expect(state.cards.value).toHaveLength(3)
    expect(mocks.getTeam).toHaveBeenCalledTimes(2)
  })

  it('権限取得失敗を権限なしや空一覧へ変換しない', async () => {
    const error = { statusCode: 503 }
    mocks.loadPermissions.mockResolvedValue({ ok: false, error })
    const state = useMemberCardManagement('team', ref('team-slug'))
    await state.loadData()
    expect(state.loadError.value).toBe(error)
    expect(mocks.listByTeam).not.toHaveBeenCalled()
  })

  it('内部ID欠落時はslugを数値APIへ送らない', async () => {
    mocks.getTeam.mockResolvedValue({ data: { id: 'team-slug' } })
    const state = useMemberCardManagement('team', ref('team-slug'))
    await state.loadData()
    expect(state.loadError.value).toBeInstanceOf(Error)
    expect(mocks.listByTeam).not.toHaveBeenCalled()
  })

  it('停止・再開後は全状態を再取得する', async () => {
    const state = useMemberCardManagement('team', ref('team-slug'))
    await state.changeStatus(20, 'suspend')
    await state.changeStatus(20, 'reactivate')
    expect(mocks.suspend).toHaveBeenCalledWith(20)
    expect(mocks.reactivate).toHaveBeenCalledWith(20)
    expect(mocks.listByTeam).toHaveBeenCalledTimes(6)
  })
})
