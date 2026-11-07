import { beforeEach, describe, expect, it, vi } from 'vitest'
import { usePermissionCatalogApi } from '~/composables/usePermissionCatalogApi'

/** F01.2.1 8-D — 権限カタログ API（GET /api/v1/permissions?scopeType=&scopeId=）。 */
const apiMock = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => apiMock }))

describe('usePermissionCatalogApi', () => {
  beforeEach(() => {
    apiMock.mockReset()
    apiMock.mockResolvedValue({ data: [{ id: 1, name: 'MANAGE_ORG_AFFILIATION' }] })
  })

  it('PC-01 スコープ種別と ID を付けて取得し、data を返す', async () => {
    const r = await usePermissionCatalogApi().listPermissionCatalog('TEAM', 77)
    expect(apiMock).toHaveBeenCalledWith('/api/v1/permissions?scopeType=TEAM&scopeId=77')
    expect(r).toEqual([{ id: 1, name: 'MANAGE_ORG_AFFILIATION' }])
  })

  it('PC-02 data が無ければ空配列', async () => {
    apiMock.mockResolvedValue({})
    expect(await usePermissionCatalogApi().listPermissionCatalog('TEAM', 1)).toEqual([])
  })
})
