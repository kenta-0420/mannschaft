import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useTeamAffiliationApi } from '~/composables/useTeamAffiliationApi'

/**
 * F01.2.1 8-D — チーム側の加盟 API クライアントのパス・メソッド・ボディ。
 *   TAP-01..09 各エンドポイント（BE: TeamOrgInviteController / TeamOrgApplicationController ほか）
 */
const apiMock = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => apiMock }))

describe('useTeamAffiliationApi', () => {
  beforeEach(() => {
    apiMock.mockReset()
    apiMock.mockResolvedValue({ data: [{ id: 1 }], meta: { total: 7, totalPages: 2 } })
  })

  it('TAP-01 所属組織一覧', async () => {
    const r = await useTeamAffiliationApi().listOrganizations('t 1')
    expect(apiMock).toHaveBeenCalledWith('/api/v1/teams/t%201/organizations')
    expect(r).toEqual([{ id: 1 }])
  })
  it('TAP-02 受信招待一覧（ページ付き）', async () => {
    const r = await useTeamAffiliationApi().listInvites('t1', 1, 20)
    expect(apiMock).toHaveBeenCalledWith('/api/v1/teams/t1/org-invites?page=1&size=20')
    expect(r).toEqual({ items: [{ id: 1 }], total: 7, totalPages: 2 })
  })
  it('TAP-03 承諾', async () => {
    await useTeamAffiliationApi().acceptInvite('t1', 5)
    expect(apiMock).toHaveBeenCalledWith('/api/v1/teams/t1/org-invites/5/accept', { method: 'POST' })
  })
  it('TAP-04 辞退（block を送る）', async () => {
    await useTeamAffiliationApi().declineInvite('t1', 5, true)
    expect(apiMock).toHaveBeenCalledWith('/api/v1/teams/t1/org-invites/5/reject', {
      method: 'POST',
      body: { block: true },
    })
  })
  it('TAP-05 申請中一覧', async () => {
    await useTeamAffiliationApi().listApplications('t1', 0, 20)
    expect(apiMock).toHaveBeenCalledWith('/api/v1/teams/t1/org-applications?page=0&size=20')
  })
  it('TAP-06 取下げ', async () => {
    await useTeamAffiliationApi().withdrawApplication('t1', 9)
    expect(apiMock).toHaveBeenCalledWith('/api/v1/teams/t1/org-applications/9', { method: 'DELETE' })
  })
  it('TAP-07 招待の制限一覧', async () => {
    await useTeamAffiliationApi().listRestrictions('t1', 2, 10)
    expect(apiMock).toHaveBeenCalledWith('/api/v1/teams/t1/org-affiliation-restrictions?page=2&size=10')
  })
  it('TAP-08 制限の解除', async () => {
    await useTeamAffiliationApi().liftRestriction('t1', 'abc-1')
    expect(apiMock).toHaveBeenCalledWith('/api/v1/teams/t1/org-affiliation-restrictions/abc-1', { method: 'DELETE' })
  })
  it('TAP-09 離脱', async () => {
    await useTeamAffiliationApi().leaveOrganization('t1', 'org-1')
    expect(apiMock).toHaveBeenCalledWith('/api/v1/teams/t1/organizations/org-1', { method: 'DELETE' })
  })
})
