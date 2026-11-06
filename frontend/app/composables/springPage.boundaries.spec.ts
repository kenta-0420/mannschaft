import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { springPage } from '../../tests/fixtures/spring-page'
import { useEmailOutboxAdminApi } from './useEmailOutboxAdminApi'
import { useCommitteeApi } from './useCommitteeApi'
import { useSystemAdminDashboard } from './system-admin/useSystemAdminDashboard'
import { useAdminReportApi } from './useAdminReportApi'

const { rawApi } = vi.hoisted(() => ({ rawApi: vi.fn() }))
mockNuxtImport('useApi', () => () => rawApi)

/** BEのWをraw HTTPへ渡し、本物のcomposableの返却を観測する。 */
describe('Spring Pageの6実API境界', () => {
  beforeEach(() => {
    // mock関数自体をcleanupとして返さない。
    rawApi.mockReset()
  })

  const adapted = [
    ['outbox', () => useEmailOutboxAdminApi().fetchList({ page: 2, size: 20 }), '/api/v1/system-admin/email-outbox?page=2&size=20'],
    ['committee一覧', () => useCommitteeApi().listCommittees('7', { page: 2, size: 20 }), '/api/v1/organizations/7/committees?page=2&size=20'],
    ['committee配布履歴', () => useCommitteeApi().listDistributions(9, { page: 2, size: 20 }), '/api/v1/committees/9/distributions?page=2&size=20'],
    ['dashboard users', () => useSystemAdminDashboard().getDashboardUsers({ page: 2, size: 20 }), '/api/v1/system-admin/dashboard/users?page=2&size=20'],
  ] as const

  it.each(adapted)('%sはWを配列と正しい総件数に変換する', async (_name, call, url) => {
    const content = [{ id: 'row-41', name: '最終行' }]
    rawApi.mockResolvedValue({ data: springPage(content, 41, 2), message: null })

    const result = await call()

    expect(rawApi).toHaveBeenCalledExactlyOnceWith(url)
    expect(result).toEqual({ data: content, meta: { page: 2, size: 20, total: 41, totalPages: 3 } })
  })

  it.each(adapted)('%sは空0件を0のまま維持する', async (_name, call) => {
    rawApi.mockResolvedValue({ data: springPage([], 0), message: null })
    expect(await call()).toEqual({ data: [], meta: { page: 0, size: 20, total: 0, totalPages: 0 } })
  })

  it.each(adapted)('%sはHTTP失敗を空成功に置き換えない', async (_name, call, url) => {
    const error = new Error('network')
    rawApi.mockRejectedValue(error)
    await expect(call()).rejects.toBe(error)
    expect(rawApi).toHaveBeenCalledExactlyOnceWith(url)
  })

  it('現在未使用のdashboard teamsはWを維持する（現契約固定）', async () => {
    const wire = { data: springPage([{ id: 'team' }], 41, 2), message: null }
    rawApi.mockResolvedValue(wire)
    expect(await useSystemAdminDashboard().getDashboardTeams({ page: 2 })).toBe(wire)
    expect(rawApi).toHaveBeenCalledExactlyOnceWith('/api/v1/system-admin/dashboard/teams?page=2&size=20')
  })

  it('現在未使用のadmin dashboard usersはWを維持する（現契約固定）', async () => {
    const wire = { data: springPage([{ id: 'user' }], 41, 2), message: null }
    rawApi.mockResolvedValue(wire)
    expect(await useAdminReportApi().getAdminDashboardUsers({ scopeId: 7, page: 2 })).toBe(wire)
    expect(rawApi).toHaveBeenCalledExactlyOnceWith('/api/v1/admin/dashboard/users?scopeId=7&page=2')
  })
})
