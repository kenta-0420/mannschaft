import { describe, it, expect, beforeEach, vi } from 'vitest'
import type { SlugResolveResponse } from '~/types/slug'

/**
 * slug リネーム・解決 composable のユニットテスト（BE #1542）。
 *
 * - renameTeamSlug / renameOrganizationSlug: `PUT /api/v1/{base}/{currentSlug}/slug` を body `{ newSlug }` で叩く
 * - resolveTeamSlug / resolveOrganizationSlug: 公開 EP `GET .../slug-resolve?slug=x` を叩き status/canonicalSlug を返す
 */

const mockApiFetch = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApiFetch,
}))

vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({
    handleApiError: vi.fn(),
    getFieldErrors: vi.fn(() => ({})),
  }),
}))

const { useTeamApi } = await import('~/composables/useTeamApi')
const { useOrganizationApi } = await import('~/composables/useOrganizationApi')

describe('renameTeamSlug / renameOrganizationSlug', () => {
  beforeEach(() => {
    mockApiFetch.mockReset()
  })

  it('チーム: `PUT /api/v1/teams/{currentSlug}/slug` を body { newSlug } で叩く', async () => {
    mockApiFetch.mockResolvedValueOnce({ data: { slug: 'new-slug' } })
    const { renameTeamSlug } = useTeamApi()
    await renameTeamSlug('old-slug', 'new-slug')

    expect(mockApiFetch).toHaveBeenCalledTimes(1)
    const [url, opts] = mockApiFetch.mock.calls[0] as [string, { method: string; body: unknown }]
    expect(url).toBe('/api/v1/teams/old-slug/slug')
    expect(opts.method).toBe('PUT')
    expect(opts.body).toEqual({ newSlug: 'new-slug' })
  })

  it('組織: `PUT /api/v1/organizations/{currentSlug}/slug` を body { newSlug } で叩く', async () => {
    mockApiFetch.mockResolvedValueOnce({ data: { slug: 'new-org' } })
    const { renameOrganizationSlug } = useOrganizationApi()
    await renameOrganizationSlug('old-org', 'new-org')

    const [url, opts] = mockApiFetch.mock.calls[0] as [string, { method: string; body: unknown }]
    expect(url).toBe('/api/v1/organizations/old-org/slug')
    expect(opts.method).toBe('PUT')
    expect(opts.body).toEqual({ newSlug: 'new-org' })
  })
})

describe('resolveTeamSlug / resolveOrganizationSlug', () => {
  beforeEach(() => {
    mockApiFetch.mockReset()
  })

  it('チーム: 公開 EP `GET /api/v1/public/teams/slug-resolve?slug=x` を叩く', async () => {
    mockApiFetch.mockResolvedValueOnce({ status: 'CURRENT' } satisfies SlugResolveResponse)
    const { resolveTeamSlug } = useTeamApi()
    const res = await resolveTeamSlug('my-team')

    const [url] = mockApiFetch.mock.calls[0] as [string]
    expect(url).toBe('/api/v1/public/teams/slug-resolve?slug=my-team')
    expect(res.status).toBe('CURRENT')
  })

  it('組織: MOVED + canonicalSlug をそのまま返す', async () => {
    mockApiFetch.mockResolvedValueOnce({
      status: 'MOVED',
      canonicalSlug: 'new-org',
    } satisfies SlugResolveResponse)
    const { resolveOrganizationSlug } = useOrganizationApi()
    const res = await resolveOrganizationSlug('old-org')

    const [url] = mockApiFetch.mock.calls[0] as [string]
    expect(url).toBe('/api/v1/public/organizations/slug-resolve?slug=old-org')
    expect(res.status).toBe('MOVED')
    expect(res.canonicalSlug).toBe('new-org')
  })

  it('NOT_FOUND をそのまま返す', async () => {
    mockApiFetch.mockResolvedValueOnce({ status: 'NOT_FOUND' } satisfies SlugResolveResponse)
    const { resolveTeamSlug } = useTeamApi()
    const res = await resolveTeamSlug('ghost')
    expect(res.status).toBe('NOT_FOUND')
    expect(res.canonicalSlug).toBeUndefined()
  })
})
