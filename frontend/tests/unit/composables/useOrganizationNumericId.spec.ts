import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useOrganizationNumericId } from '~/composables/useOrganizationNumericId'
import { useAdvertiserApi } from '~/composables/useAdvertiserApi'

const { getOrganization, advertiserRequest } = vi.hoisted(() => ({
  getOrganization: vi.fn(),
  advertiserRequest: vi.fn(),
}))

vi.mock('~/composables/useOrganizationApi', () => ({
  useOrganizationApi: () => ({ getOrganization }),
}))
vi.mock('~/composables/useApi', () => ({ useApi: () => advertiserRequest }))

// Node限定実行でもNuxtのauto-importと同じ依存を使う。Nuxt環境では上のmodule mockを使う。
beforeEach(() => {
  vi.stubGlobal('useOrganizationApi', () => ({ getOrganization }))
  vi.stubGlobal('useOrganizationNumericId', useOrganizationNumericId)
  vi.stubGlobal('useApi', () => advertiserRequest)
})
afterEach(() => vi.unstubAllGlobals())

describe('useOrganizationNumericId', () => {
  beforeEach(() => {
    getOrganization.mockReset()
  })

  it('slug で取得した組織詳細の numericId を文字列で返す', async () => {
    getOrganization.mockResolvedValue({ data: { id: 'my-org', numericId: 42 } })

    const { resolveOrganizationNumericId } = useOrganizationNumericId()

    await expect(resolveOrganizationNumericId('my-org')).resolves.toBe('42')
    expect(getOrganization).toHaveBeenCalledWith('my-org')
  })

  it.each([undefined, null, 0, -1, 1.5])(
    '有効な numericId が無い場合は拒否する (%s)',
    async (numericId) => {
      getOrganization.mockResolvedValue({ data: { id: 'my-org', numericId } })

      const { resolveOrganizationNumericId } = useOrganizationNumericId()

      await expect(resolveOrganizationNumericId('my-org')).rejects.toThrow(
        'Organization numeric ID is unavailable: my-org',
      )
    },
  )
})

const analyticsMethods = [
  ['getCampaignPerformance', 'performance'],
  ['getCreativeComparison', 'creatives'],
  ['getBreakdown', 'breakdown'],
  ['exportCampaignCsv', 'export'],
] as const
type AnalyticsMethod = (typeof analyticsMethods)[number][0]
function callAnalytics(api: ReturnType<typeof useAdvertiserApi>, method: AnalyticsMethod, scope: 'TEAM' | 'ORGANIZATION', id: string) {
  return method === 'getBreakdown'
    ? api.getBreakdown(scope, id, 7, '2026-10-01', '2026-10-21', 'PREFECTURE')
    : api[method](scope, id, 7, '2026-10-01', '2026-10-21')
}
function analyticsOptions(method: AnalyticsMethod, organizationId?: string) {
  return {
    params: {
      ...(organizationId ? { organizationId } : {}),
      from: '2026-10-01',
      to: '2026-10-21',
      ...(method === 'getBreakdown' ? { breakdownBy: 'PREFECTURE' } : {}),
    },
    ...(method === 'exportCampaignCsv' ? { responseType: 'blob' } : {}),
  }
}
describe('CMP-261014-2035 組織広告4関数の送信query', () => {
  beforeEach(() => {
    getOrganization.mockReset().mockResolvedValue({ data: { id: 'org-slug', numericId: 632 } })
    advertiserRequest.mockReset().mockResolvedValue({ data: {} })
  })
  it.each(analyticsMethods)('%sはslug呼出しを保持してqueryだけnumericIdへ解決する', async (method, resource) => {
    await callAnalytics(useAdvertiserApi(), method, 'ORGANIZATION', 'org-slug')
    expect(getOrganization).toHaveBeenCalledExactlyOnceWith('org-slug')
    expect(advertiserRequest).toHaveBeenCalledExactlyOnceWith(`/api/v1/advertiser/campaigns/7/${resource}`, analyticsOptions(method, '632'))
  })
  it.each(analyticsMethods)('%sのTEAMは組織resolverを呼ばずパスと日付を維持する', async (method, resource) => {
    await callAnalytics(useAdvertiserApi(), method, 'TEAM', 'team-slug')
    expect(getOrganization).not.toHaveBeenCalled()
    expect(advertiserRequest).toHaveBeenCalledExactlyOnceWith(`/api/v1/teams/team-slug/advertiser/campaigns/7/${resource}`, analyticsOptions(method))
  })
  it.each(analyticsMethods)('%sは組織解決失敗を伝播し広告APIへslugを送らない', async (method) => {
    const error = new Error('organization lookup failed')
    getOrganization.mockRejectedValue(error)
    await expect(callAnalytics(useAdvertiserApi(), method, 'ORGANIZATION', 'org-slug')).rejects.toBe(error)
    expect(advertiserRequest).not.toHaveBeenCalled()
  })
})
