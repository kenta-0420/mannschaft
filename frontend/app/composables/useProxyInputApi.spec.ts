// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => mockApi }))
const { useProxyInputApi } = await import('./useProxyInputApi')

describe('代理同意管理APIの実応答契約', () => {
  beforeEach(() => { mockApi.mockReset() })

  it('デスク用activeと承認はApiResponse.dataを展開する', async () => {
    const consent = { id: 41, status: 'APPROVED', organizationId: 8 }
    mockApi.mockResolvedValueOnce({ data: [consent], message: null })
    expect(await useProxyInputApi().getActiveConsents()).toEqual([consent])
    mockApi.mockResolvedValueOnce({ data: consent, message: null })
    expect(await useProxyInputApi().approveConsent(41)).toEqual(consent)
  })

  it('組合同意書一覧は標準data/metaを保持しpage/sizeを送る', async () => {
    const response = { data: [], meta: { page: 2, size: 20, total: 40, totalPages: 2 } }
    mockApi.mockResolvedValueOnce(response)
    expect(await useProxyInputApi().getConsentsByOrg('8', 2, 20)).toEqual(response)
    expect(mockApi).toHaveBeenCalledWith('/api/v1/organizations/8/proxy-input-consents', { query: { page: 2, size: 20 } })
  })

  it('履歴に組合と対象者を同時指定し標準metaを保持する', async () => {
    const response = { data: [{ consentId: null }], meta: { page: 0, size: 20, total: 1, totalPages: 1 } }
    mockApi.mockResolvedValueOnce(response)
    expect(await useProxyInputApi().getRecords({ organizationId: 8, subjectUserId: 9, page: 0, size: 20 })).toEqual(response)
    expect(mockApi).toHaveBeenCalledWith('/api/v1/proxy-input-records?organizationId=8&subjectUserId=9&page=0&size=20')
  })

  it('紙撤回は立会人・理由を送信しvoidで完了する', async () => {
    const body = { revokeMethod: 'PAPER_BY_SUBJECT' as const, revokeWitnessedByUserId: 9, revokeReason: 'a'.repeat(255) }
    mockApi.mockResolvedValueOnce({ data: null, message: null })
    expect(await useProxyInputApi().revokeConsent(41, body)).toBeUndefined()
    expect(mockApi).toHaveBeenCalledWith('/api/v1/proxy-input-consents/41/revoke', { method: 'PATCH', body })
  })

  it('mutation失敗を成功応答へ変換しない', async () => {
    const error = new Error('保存失敗')
    mockApi.mockRejectedValueOnce(error)
    await expect(useProxyInputApi().approveConsent(41)).rejects.toBe(error)
  })
})
