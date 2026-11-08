// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useTeamLoyaltyApi } from './useTeamLoyaltyApi'
const api = vi.hoisted(() => vi.fn())
vi.mock('~/composables/useApi', () => ({ useApi: () => api }))
vi.stubGlobal('useApi', () => api)
describe('チームポイントHTTP契約（AC09/17/23/30/32）', () => {
  beforeEach(() => {
    api.mockReset()
    api.mockResolvedValue({ data: {} })
  })
  it('初回bootstrapへ署名tokenだけをPOSTし、team/user/pointsを指定しない', async () => {
    await useTeamLoyaltyApi().bootstrap('signed')
    expect(api).toHaveBeenCalledWith('/api/v1/loyalty/self-award-bootstrap/me', {
      method: 'POST',
      body: { locationToken: 'signed' },
    })
  })
  it('numericIdで付与し、確認した版と同じkeyを送る', async () => {
    const body = {
      qrToken: 'signed',
      ruleId: 'rule',
      expectedRuleVersion: 2,
      expectedProgramVersion: 3,
    }
    await useTeamLoyaltyApi().award(42, body, 'request-key')
    expect(api).toHaveBeenCalledWith('/api/v1/teams/42/loyalty/awards', {
      method: 'POST',
      body,
      headers: { 'Idempotency-Key': 'request-key' },
    })
  })
  it('空dataでもhasNextとcursorをそのまま返し、自動巡回しない', async () => {
    const page = { data: [], meta: { hasNext: true, nextCursor: 'next', limit: 20 } }
    api.mockResolvedValue(page)
    expect(await useTeamLoyaltyApi().requests(42, 'PENDING')).toEqual(page)
    expect(api).toHaveBeenCalledTimes(1)
  })
})
