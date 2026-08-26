import { describe, it, expect, beforeEach, vi } from 'vitest'
import type { FetchError, FetchResponse } from 'ofetch'
import type { PagedResponse } from '~/types/api'
import {
  OrganizationNotFoundError,
  TeamSearchRateLimitError,
  isTeamSearchResult,
  type TeamPublicSummary,
  type TeamSearchItem,
  type TeamSearchResult,
} from '~/types/team-search'

/**
 * F15.4 useTeamApi.searchOrganizationTeams のユニットテスト。
 *
 * テストケース:
 * 1. クエリ組み立て — undefined フィールドが URL から除外されること
 * 2. 200 レスポンスを `PagedResponse<TeamSearchItem>` として返すこと
 * 3. `isTeamSearchResult` タイプガードが詳細版と抑制版を判別すること
 * 4. 404 → `OrganizationNotFoundError` に変換してスローすること
 * 5. 429 → `TeamSearchRateLimitError`（Retry-After 含む）に変換してスローすること
 * 6. それ以外のエラー（500 等）は元の FetchError がそのまま伝播すること
 */

// useApi のモック（ofetch ラッパーを模倣する関数）
const mockApiFetch = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApiFetch,
}))

// useErrorHandler のモック（useTeamApi が依存している）
vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({
    handleApiError: vi.fn(),
  }),
}))

// テスト対象を動的 import（モック設定後）
const { useTeamApi } = await import('~/composables/useTeamApi')

// ============================================================
// ヘルパー
// ============================================================

function makePublicSummary(over: Partial<TeamPublicSummary> = {}): TeamPublicSummary {
  return {
    id: 1,
    slug: 'team-slug-1',
    name: '本店',
    nameKana: 'ホンテン',
    prefecture: '東京都',
    city: '千代田区',
    prefectureCode: '13',
    cityCode: '13101',
    template: 'STORE',
    iconUrl: null,
    ...over,
  }
}

function makeSearchResult(over: Partial<TeamSearchResult> = {}): TeamSearchResult {
  return {
    ...makePublicSummary(),
    visibility: 'GUESTS_AND_ABOVE',
    bannerUrl: null,
    supporterEnabled: false,
    ...over,
  }
}

function makePagedResponse(data: TeamSearchItem[]): PagedResponse<TeamSearchItem> {
  return {
    data,
    meta: {
      page: 0,
      size: 20,
      totalElements: data.length,
      totalPages: 1,
    },
  }
}

/**
 * FetchError 互換のオブジェクトを生成する。
 * ofetch の FetchError は `response.status` / `response.headers` を参照するので、
 * 必要な部分のみを満たした擬似オブジェクトを作る。
 */
function makeFetchError(
  status: number,
  headers: Record<string, string> = {},
): FetchError {
  const headerObj = new Headers(headers)
  const response = {
    status,
    headers: headerObj,
  } as unknown as FetchResponse<unknown>
  const err = new Error(`HTTP ${status}`) as FetchError
  ;(err as { response?: FetchResponse<unknown> }).response = response
  return err
}

// ============================================================
// テスト本体
// ============================================================

describe('useTeamApi.searchOrganizationTeams', () => {
  beforeEach(() => {
    mockApiFetch.mockReset()
  })

  it('undefined のクエリパラメータが URL に含まれないこと', async () => {
    mockApiFetch.mockResolvedValueOnce(makePagedResponse([]))

    const { searchOrganizationTeams } = useTeamApi()
    await searchOrganizationTeams('42', {
      keyword: '本店',
      // prefecture / city / template / page / size / sort は undefined
    })

    expect(mockApiFetch).toHaveBeenCalledTimes(1)
    const [calledUrl] = mockApiFetch.mock.calls[0] as [string]
    expect(calledUrl.startsWith('/api/v1/organizations/42/teams/search')).toBe(true)
    expect(calledUrl).toContain('keyword=')
    expect(calledUrl).not.toContain('prefecture=')
    expect(calledUrl).not.toContain('city=')
    expect(calledUrl).not.toContain('template=')
    expect(calledUrl).not.toContain('page=')
    expect(calledUrl).not.toContain('size=')
    expect(calledUrl).not.toContain('sort=')
  })

  it('すべてのクエリパラメータを正しくシリアライズすること', async () => {
    mockApiFetch.mockResolvedValueOnce(makePagedResponse([]))

    const { searchOrganizationTeams } = useTeamApi()
    await searchOrganizationTeams('org-7', {
      keyword: 'カフェ',
      prefecture: '大阪府',
      city: '大阪市',
      template: 'STORE',
      page: 2,
      size: 50,
      sort: 'nameKana,asc',
    })

    const [calledUrl] = mockApiFetch.mock.calls[0] as [string]
    expect(calledUrl.startsWith('/api/v1/organizations/org-7/teams/search?')).toBe(true)
    const qs = new URLSearchParams(calledUrl.split('?')[1])
    expect(qs.get('keyword')).toBe('カフェ')
    expect(qs.get('prefecture')).toBe('大阪府')
    expect(qs.get('city')).toBe('大阪市')
    expect(qs.get('template')).toBe('STORE')
    expect(qs.get('page')).toBe('2')
    expect(qs.get('size')).toBe('50')
    expect(qs.get('sort')).toBe('nameKana,asc')
  })

  it('F22.1: prefectureCode/cityCode を camelCase で送ること（コード優先）', async () => {
    mockApiFetch.mockResolvedValueOnce(makePagedResponse([]))

    const { searchOrganizationTeams } = useTeamApi()
    await searchOrganizationTeams('42', {
      keyword: 'カフェ',
      prefectureCode: '13',
      cityCode: '13101',
      template: 'STORE',
    })

    const [calledUrl] = mockApiFetch.mock.calls[0] as [string]
    const qs = new URLSearchParams(calledUrl.split('?')[1])
    // BE @RequestParam prefectureCode/cityCode（camelCase）と 1:1
    expect(qs.get('prefectureCode')).toBe('13')
    expect(qs.get('cityCode')).toBe('13101')
    expect(qs.get('template')).toBe('STORE')
  })

  it('F22.1: コード指定時は名称(prefecture/city)を送らない（コード優先・dual-support）', async () => {
    mockApiFetch.mockResolvedValueOnce(makePagedResponse([]))

    const { searchOrganizationTeams } = useTeamApi()
    await searchOrganizationTeams('42', {
      prefecture: '東京都',
      prefectureCode: '13',
      city: '千代田区',
      cityCode: '13101',
    })

    const [calledUrl] = mockApiFetch.mock.calls[0] as [string]
    const qs = new URLSearchParams(calledUrl.split('?')[1])
    expect(qs.get('prefectureCode')).toBe('13')
    expect(qs.get('cityCode')).toBe('13101')
    // コードがあるので名称は送られない
    expect(qs.get('prefecture')).toBeNull()
    expect(qs.get('city')).toBeNull()
  })

  it('200 レスポンスを PagedResponse<TeamSearchItem> として返すこと', async () => {
    const items: TeamSearchItem[] = [
      makeSearchResult({ id: 1, name: '本店' }),
      makePublicSummary({ id: 2, name: '支店' }),
    ]
    mockApiFetch.mockResolvedValueOnce(makePagedResponse(items))

    const { searchOrganizationTeams } = useTeamApi()
    const result = await searchOrganizationTeams('42', { keyword: '店' })

    expect(result.data).toHaveLength(2)
    expect(result.data[0]!.id).toBe(1)
    expect(result.meta.totalElements).toBe(2)
  })

  it('isTeamSearchResult タイプガードが詳細版と抑制版を判別すること', () => {
    const summary: TeamSearchItem = makePublicSummary()
    const detail: TeamSearchItem = makeSearchResult()

    expect(isTeamSearchResult(summary)).toBe(false)
    expect(isTeamSearchResult(detail)).toBe(true)

    // 詳細版と判定された場合は visibility / bannerUrl / supporterEnabled にアクセスできる
    if (isTeamSearchResult(detail)) {
      expect(detail.visibility).toBe('GUESTS_AND_ABOVE')
      expect(detail.supporterEnabled).toBe(false)
    }
  })

  it('404 は OrganizationNotFoundError に変換してスローすること', async () => {
    mockApiFetch.mockRejectedValueOnce(makeFetchError(404))

    const { searchOrganizationTeams } = useTeamApi()
    await expect(searchOrganizationTeams('999', {})).rejects.toBeInstanceOf(
      OrganizationNotFoundError,
    )

    // organizationId が保持されていることを確認
    try {
      await searchOrganizationTeams('999', {})
    } catch (e) {
      // 2 回目のモックを設定しなおす必要があるためここでは型のみ確認
      expect(e).toBeDefined()
    }
  })

  it('OrganizationNotFoundError は organizationId を保持すること', async () => {
    mockApiFetch.mockRejectedValueOnce(makeFetchError(404))

    const { searchOrganizationTeams } = useTeamApi()
    let captured: unknown = null
    try {
      await searchOrganizationTeams('777', {})
    } catch (e) {
      captured = e
    }
    expect(captured).toBeInstanceOf(OrganizationNotFoundError)
    expect((captured as OrganizationNotFoundError).organizationId).toBe('777')
  })

  it('429 は TeamSearchRateLimitError に変換してスローし Retry-After を保持すること', async () => {
    mockApiFetch.mockRejectedValueOnce(
      makeFetchError(429, { 'Retry-After': '30' }),
    )

    const { searchOrganizationTeams } = useTeamApi()
    let captured: unknown = null
    try {
      await searchOrganizationTeams('42', {})
    } catch (e) {
      captured = e
    }
    expect(captured).toBeInstanceOf(TeamSearchRateLimitError)
    expect((captured as TeamSearchRateLimitError).retryAfterSeconds).toBe(30)
  })

  it('429 で Retry-After ヘッダ未設定の場合 retryAfterSeconds は null', async () => {
    mockApiFetch.mockRejectedValueOnce(makeFetchError(429))

    const { searchOrganizationTeams } = useTeamApi()
    let captured: unknown = null
    try {
      await searchOrganizationTeams('42', {})
    } catch (e) {
      captured = e
    }
    expect(captured).toBeInstanceOf(TeamSearchRateLimitError)
    expect((captured as TeamSearchRateLimitError).retryAfterSeconds).toBeNull()
  })

  it('500 などその他のエラーは元の FetchError をそのままスローすること', async () => {
    const original = makeFetchError(500)
    mockApiFetch.mockRejectedValueOnce(original)

    const { searchOrganizationTeams } = useTeamApi()
    let captured: unknown = null
    try {
      await searchOrganizationTeams('42', {})
    } catch (e) {
      captured = e
    }
    expect(captured).toBe(original)
    expect(captured).not.toBeInstanceOf(OrganizationNotFoundError)
    expect(captured).not.toBeInstanceOf(TeamSearchRateLimitError)
  })
})
