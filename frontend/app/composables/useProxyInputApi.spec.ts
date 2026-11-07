import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useProxyInputApi } from './useProxyInputApi'

const { api } = vi.hoisted(() => ({ api: vi.fn() }))
mockNuxtImport('useApi', () => () => api)

describe('useProxyInputApi', () => {
  beforeEach(() => {
    api.mockReset()
  })

  it('activeとapproveはApiResponse.dataを取り出す', async () => {
    api.mockResolvedValueOnce({ data: [{ id: 7, organizationId: 10 }] })
    api.mockResolvedValueOnce({ data: { id: 7, status: 'APPROVED' } })
    const proxy = useProxyInputApi()

    expect(await proxy.getActiveConsents()).toEqual([{ id: 7, organizationId: 10 }])
    expect(await proxy.approveConsent(7)).toEqual({ id: 7, status: 'APPROVED' })
    expect(api).toHaveBeenLastCalledWith('/api/v1/proxy-input-consents/7/approve', {
      method: 'PATCH',
    })
  })

  it('同意一覧はmetaを保持してpageとsizeを送る', async () => {
    const page = { data: [], meta: { total: 0, page: 2, size: 50, totalPages: 0 } }
    api.mockResolvedValue(page)

    expect(await useProxyInputApi().getConsentsByOrg('10', { page: 2, size: 50 })).toEqual(page)
    expect(api).toHaveBeenCalledWith('/api/v1/organizations/10/proxy-input-consents', {
      query: { page: 2, size: 50 },
    })
  })

  it('履歴一覧は自組合organizationIdとページ条件を送り実recordを返す', async () => {
    const page = {
      data: [{ id: 90, proxyInputConsentId: 7 }],
      meta: { total: 25, page: 1, size: 20, totalPages: 2 },
    }
    api.mockResolvedValue(page)

    expect(await useProxyInputApi().getRecords({ organizationId: 10, page: 1, size: 20 })).toEqual(
      page,
    )
    expect(api).toHaveBeenCalledWith('/api/v1/proxy-input-records?organizationId=10&page=1&size=20')
  })

  it('紙撤回は正式enumを送りVoid成功を受け取る', async () => {
    api.mockResolvedValue({ data: null })
    const request = { revokeMethod: 'PAPER_BY_SUBJECT' as const }

    expect(await useProxyInputApi().revokeConsent(7, request)).toBeUndefined()
    expect(api).toHaveBeenCalledWith('/api/v1/proxy-input-consents/7/revoke', {
      method: 'PATCH',
      body: request,
    })
  })

  it('競合や権限エラーを成功として返さない', async () => {
    const failure = Object.assign(new Error('同意書の状態競合'), { statusCode: 409 })
    api.mockRejectedValue(failure)

    await expect(useProxyInputApi().approveConsent(7)).rejects.toBe(failure)
    await expect(
      useProxyInputApi().revokeConsent(7, { revokeMethod: 'API_BY_SUBJECT' }),
    ).rejects.toBe(failure)
  })
})
