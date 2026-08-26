import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * useTeamApi.checkTeamSlugAvailable / useOrganizationApi.checkOrganizationSlugAvailable
 * のユニットテスト（村方式 slug 入力 UX・BE #1538）。
 *
 * テストケース:
 * 1. チーム: `/api/v1/teams/slug-available?slug=xxx` を叩くこと
 * 2. 組織: `/api/v1/organizations/slug-available?slug=xxx` を叩くこと
 * 3. available=true / available=false(reason) のレスポンスをそのまま返すこと
 */

const mockApiFetch = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApiFetch,
}))

vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({
    handleApiError: vi.fn(),
  }),
}))

const { useTeamApi } = await import('~/composables/useTeamApi')
const { useOrganizationApi } = await import('~/composables/useOrganizationApi')

describe('useTeamApi.checkTeamSlugAvailable', () => {
  beforeEach(() => {
    mockApiFetch.mockReset()
  })

  it('`/api/v1/teams/slug-available?slug=xxx` を叩くこと', async () => {
    mockApiFetch.mockResolvedValueOnce({ data: { available: true } })
    const { checkTeamSlugAvailable } = useTeamApi()
    await checkTeamSlugAvailable('my-team')

    expect(mockApiFetch).toHaveBeenCalledTimes(1)
    const [calledUrl] = mockApiFetch.mock.calls[0] as [string]
    expect(calledUrl).toBe('/api/v1/teams/slug-available?slug=my-team')
  })

  it('available=true をそのまま返す', async () => {
    mockApiFetch.mockResolvedValueOnce({ data: { available: true } })
    const { checkTeamSlugAvailable } = useTeamApi()
    const res = await checkTeamSlugAvailable('free-slug')
    expect(res.available).toBe(true)
    expect(res.reason).toBeUndefined()
  })

  it('available=false の reason をそのまま返す', async () => {
    mockApiFetch.mockResolvedValueOnce({
      data: { available: false, reason: 'SLUG_ALREADY_TAKEN' },
    })
    const { checkTeamSlugAvailable } = useTeamApi()
    const res = await checkTeamSlugAvailable('taken-slug')
    expect(res.available).toBe(false)
    expect(res.reason).toBe('SLUG_ALREADY_TAKEN')
  })

  it('予約語の reason を返す', async () => {
    mockApiFetch.mockResolvedValueOnce({
      data: { available: false, reason: 'SLUG_RESERVED' },
    })
    const { checkTeamSlugAvailable } = useTeamApi()
    const res = await checkTeamSlugAvailable('admin')
    expect(res.reason).toBe('SLUG_RESERVED')
  })
})

describe('useOrganizationApi.checkOrganizationSlugAvailable', () => {
  beforeEach(() => {
    mockApiFetch.mockReset()
  })

  it('`/api/v1/organizations/slug-available?slug=xxx` を叩くこと', async () => {
    mockApiFetch.mockResolvedValueOnce({ data: { available: true } })
    const { checkOrganizationSlugAvailable } = useOrganizationApi()
    await checkOrganizationSlugAvailable('my-org')

    expect(mockApiFetch).toHaveBeenCalledTimes(1)
    const [calledUrl] = mockApiFetch.mock.calls[0] as [string]
    expect(calledUrl).toBe('/api/v1/organizations/slug-available?slug=my-org')
  })

  it('available=false(invalid format) の reason を返す', async () => {
    mockApiFetch.mockResolvedValueOnce({
      data: { available: false, reason: 'SLUG_INVALID_FORMAT' },
    })
    const { checkOrganizationSlugAvailable } = useOrganizationApi()
    const res = await checkOrganizationSlugAvailable('-bad-')
    expect(res.available).toBe(false)
    expect(res.reason).toBe('SLUG_INVALID_FORMAT')
  })
})
