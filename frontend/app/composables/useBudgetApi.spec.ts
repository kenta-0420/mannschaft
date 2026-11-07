// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useBudgetApi } from './useBudgetApi'
const api = vi.fn()
mockNuxtImport('useApi', () => () => api)
beforeEach(() => api.mockReset())
describe('予算API契約', () => {
  it.each(['team', 'organization'] as const)(
    '集計は%sでも実在する年度IDの共通URLを使う',
    async (scope) => {
      api.mockResolvedValue({ data: { categories: [] } })
      await useBudgetApi().getSummary(scope, 'my-scope', 42)
      expect(api).toHaveBeenCalledExactlyOnceWith('/api/v1/budget/fiscal-years/42/summary')
    },
  )
  it('チームslugの年度作成は期間3項目をそのまま送る', async () => {
    const body = { name: '2026年度', startDate: '2026-04-01', endDate: '2027-03-31' }
    await useBudgetApi().createFiscalYear('team', 'my-team', body)
    expect(api).toHaveBeenCalledExactlyOnceWith('/api/v1/teams/my-team/budget/fiscal-years', {
      method: 'POST',
      body,
    })
  })
})
