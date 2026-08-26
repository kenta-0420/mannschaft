import { describe, it, expect, beforeEach, vi } from 'vitest'
import type { FetchError, FetchResponse } from 'ofetch'
import type { TeamPublicDetailResponse } from '~/types/team'

/**
 * F15.4 Phase 5-γ useTeamApi.getPublicTeam のユニットテスト。
 *
 * テストケース:
 * 1. クエリ URL が `/api/v1/public/teams/{id}` であること
 * 2. 200 レスポンスを `{ data: TeamPublicDetailResponse }` で返すこと
 * 3. 404 / 429 / 5xx は ofetch の FetchError がそのまま伝播すること
 *    (公開 API は呼び出し側でハンドリングするためエラーラッピングはしない設計)
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

function makeFetchError(status: number): FetchError {
  const response = {
    status,
    headers: new Headers(),
  } as unknown as FetchResponse<unknown>
  const err = new Error(`HTTP ${status}`) as FetchError
  ;(err as { response?: FetchResponse<unknown> }).response = response
  return err
}

function makePublicTeam(
  over: Partial<TeamPublicDetailResponse> = {},
): TeamPublicDetailResponse {
  return {
    id: '8001',
    slug: 'test-team',
    name: '本店',
    nameKana: null,
    nickname1: null,
    nickname2: null,
    template: 'STORE',
    prefecture: '東京都',
    city: '千代田区',
    iconUrl: null,
    bannerUrl: null,
    homepageUrl: null,
    establishedDate: null,
    establishedDatePrecision: null,
    philosophy: null,
    memberCount: null,
    mapEmbedUrl: null,
    ...over,
  }
}

describe('useTeamApi.getPublicTeam', () => {
  beforeEach(() => {
    mockApiFetch.mockReset()
  })

  it('指定 ID で `/api/v1/public/teams/{id}` を呼ぶこと', async () => {
    mockApiFetch.mockResolvedValueOnce({ data: makePublicTeam({ id: '8001' }) })

    const { getPublicTeam } = useTeamApi()
    await getPublicTeam('8001')

    expect(mockApiFetch).toHaveBeenCalledTimes(1)
    const [calledUrl] = mockApiFetch.mock.calls[0] as [string]
    expect(calledUrl).toBe('/api/v1/public/teams/8001')
  })

  it('200 レスポンスを `{ data: TeamPublicDetailResponse }` で返すこと', async () => {
    const team = makePublicTeam({
      id: '9001',
      name: 'カフェ・公開店',
      philosophy: '地域に根ざして',
      memberCount: 7,
      mapEmbedUrl: 'https://www.google.com/maps/embed?pb=test',
    })
    mockApiFetch.mockResolvedValueOnce({ data: team })

    const { getPublicTeam } = useTeamApi()
    const result = await getPublicTeam('9001')

    expect(result.data.id).toBe('9001')
    expect(result.data.name).toBe('カフェ・公開店')
    expect(result.data.philosophy).toBe('地域に根ざして')
    expect(result.data.memberCount).toBe(7)
    expect(result.data.mapEmbedUrl).toContain('google.com/maps/embed')
  })

  it('404 は FetchError がそのまま伝播すること（ラッピングなし）', async () => {
    const original = makeFetchError(404)
    mockApiFetch.mockRejectedValueOnce(original)

    const { getPublicTeam } = useTeamApi()
    let captured: unknown = null
    try {
      await getPublicTeam('9999')
    } catch (e) {
      captured = e
    }
    expect(captured).toBe(original)
  })

  it('429 / 500 もそのまま伝播', async () => {
    for (const status of [429, 500]) {
      mockApiFetch.mockRejectedValueOnce(makeFetchError(status))
      const { getPublicTeam } = useTeamApi()
      await expect(getPublicTeam('8001')).rejects.toBeDefined()
    }
  })
})
