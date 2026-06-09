import type { FetchError } from 'ofetch'
import type { PagedResponse } from '~/types/api'
import type { TeamPublicDetailResponse, TeamResponse } from '~/types/team'
import {
  OrganizationNotFoundError,
  TeamSearchRateLimitError,
  type TeamSearchItem,
  type TeamSearchQuery,
} from '~/types/team-search'

interface TeamSummaryResponse {
  id: string
  name: string
  nickname1: string | null
  iconUrl: string | null
  prefecture: string | null
  city: string | null
  /** 都道府県コード（BE `prefectureCode` camelCase と 1:1、null 許容）。 */
  prefectureCode: string | null
  /** 市区町村コード（BE `cityCode` camelCase と 1:1、null 許容）。 */
  cityCode: string | null
  template: string
  memberCount: number
  supporterEnabled: boolean
}

interface PagedData<T> {
  data: T[]
  meta: { page: number; size: number; totalElements: number; totalPages: number }
}

/**
 * チーム CRUD・検索・アーカイブ・組織一覧・オーナー移譲を扱うサブ composable。
 *
 * useTeamApi を分割した責務マップのうち「チーム本体」に対する操作を担当する。
 * 公開関数のシグネチャは元の useTeamApi と同一を維持している。
 */
export function useTeamCrud() {
  const api = useApi()

  // === CRUD ===
  async function getTeam(teamSlug: string) {
    return api<{ data: TeamResponse }>(`/api/v1/teams/${teamSlug}`)
  }

  /**
   * F15.4 Phase 5-α: 未ログイン公開チーム詳細取得。
   * `GET /api/v1/public/teams/{publicId}` を呼ぶ（permitAll、レート制限 60/min/IP）。
   *
   * - 404: 不在 / 削除済み / archived / visibility != PUBLIC
   * - 429: レート制限超過（呼び出し元で扱う）
   *
   * バックエンドのレスポンスは `{ data: TeamPublicDetailResponse }` 形式。
   */
  async function getPublicTeam(teamSlug: string) {
    return api<{ data: TeamPublicDetailResponse }>(`/api/v1/public/teams/${teamSlug}`)
  }

  /**
   * 公開チーム検索（`GET /api/v1/teams/search`）。
   *
   * F22.1 Phase2 足場C 第三陣: 地域フィルタはコード送信（`prefectureCode`）を優先する。
   * BE `PublicDiscoverController` の `@RequestParam prefectureCode`（camelCase）と 1:1。
   * `prefecture`（名称）は後方互換のフォールバックとして残置。
   * ※公開チーム検索 API には `cityCode` パラメータは存在しない（BE 仕様に合わせ送らない）。
   */
  async function searchTeams(params: {
    keyword?: string
    prefecture?: string
    prefectureCode?: string
    template?: string
    page?: number
    size?: number
  }) {
    const query = new URLSearchParams()
    if (params.keyword) query.set('keyword', params.keyword)
    // コード優先・名称フォールバック（BE dual-support）。
    if (params.prefectureCode) query.set('prefectureCode', params.prefectureCode)
    else if (params.prefecture) query.set('prefecture', params.prefecture)
    if (params.template) query.set('template', params.template)
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 20))
    return api<PagedData<TeamSummaryResponse>>(`/api/v1/teams/search?${query}`)
  }

  /**
   * F15.4 組織内チーム（店舗）検索。
   * 指定された組織に属するチームを keyword / prefecture / city / template で絞り込む。
   *
   * - undefined のクエリパラメータは URL から除外される
   * - 404: 組織が存在しない → {@link OrganizationNotFoundError}
   * - 429: レート制限超過 → {@link TeamSearchRateLimitError}
   * - その他のエラーはそのままスロー（ofetch の `FetchError`）
   *
   * @param orgId 組織 ID
   * @param query 検索クエリ
   * @returns ページング済みの検索結果。閲覧者の権限に応じて要素は
   *          `TeamPublicSummary` または `TeamSearchResult` の union 型になる
   *          （判定は `isTeamSearchResult` タイプガードで行う）。
   */
  async function searchOrganizationTeams(
    orgId: string | string,
    query: TeamSearchQuery,
  ): Promise<PagedResponse<TeamSearchItem>> {
    const params = new URLSearchParams()
    if (query.keyword !== undefined) params.set('keyword', query.keyword)
    // F22.1 Phase2 足場C 第三陣: コード優先・名称フォールバック（BE dual-support）。
    // BE `OrganizationTeamSearchController` の @RequestParam prefectureCode/cityCode（camelCase）と 1:1。
    if (query.prefectureCode !== undefined) params.set('prefectureCode', query.prefectureCode)
    else if (query.prefecture !== undefined) params.set('prefecture', query.prefecture)
    if (query.cityCode !== undefined) params.set('cityCode', query.cityCode)
    else if (query.city !== undefined) params.set('city', query.city)
    if (query.template !== undefined) params.set('template', query.template)
    if (query.page !== undefined) params.set('page', String(query.page))
    if (query.size !== undefined) params.set('size', String(query.size))
    if (query.sort !== undefined) params.set('sort', query.sort)

    const qs = params.toString()
    const url =
      `/api/v1/organizations/${orgId}/teams/search` + (qs.length > 0 ? `?${qs}` : '')

    try {
      return await api<PagedResponse<TeamSearchItem>>(url)
    } catch (error) {
      const fetchError = error as FetchError
      const status = fetchError?.response?.status
      if (status === 404) {
        throw new OrganizationNotFoundError(orgId)
      }
      if (status === 429) {
        const retryAfter = fetchError.response?.headers.get('Retry-After')
        const retryAfterSeconds =
          retryAfter !== null && retryAfter !== undefined && retryAfter !== ''
            ? Number.parseInt(retryAfter, 10)
            : null
        throw new TeamSearchRateLimitError(
          Number.isFinite(retryAfterSeconds) ? retryAfterSeconds : null,
        )
      }
      throw error
    }
  }

  async function createTeam(body: Record<string, unknown>) {
    return api<{ data: TeamResponse }>('/api/v1/teams', { method: 'POST', body })
  }

  async function updateTeam(teamSlug: string, body: Record<string, unknown>) {
    return api<{ data: TeamResponse }>(`/api/v1/teams/${teamSlug}`, { method: 'PATCH', body })
  }

  async function deleteTeam(teamSlug: string) {
    return api(`/api/v1/teams/${teamSlug}`, { method: 'DELETE' })
  }

  // === アーカイブ ===
  async function archiveTeam(teamSlug: string) {
    return api(`/api/v1/teams/${teamSlug}/archive`, { method: 'PATCH' })
  }

  async function unarchiveTeam(teamSlug: string) {
    return api(`/api/v1/teams/${teamSlug}/unarchive`, { method: 'PATCH' })
  }

  async function restoreTeam(teamSlug: string) {
    return api(`/api/v1/teams/${teamSlug}/restore`, { method: 'PATCH' })
  }

  // === 組織一覧 ===
  async function getOrganizations(teamSlug: string) {
    return api<{ data: Array<Record<string, unknown>> }>(`/api/v1/teams/${teamSlug}/organizations`)
  }

  // === オーナー移譲 ===
  async function transferOwnership(teamSlug: string, newAdminUserId: number) {
    return api(`/api/v1/teams/${teamSlug}/transfer-ownership`, {
      method: 'POST',
      body: { newAdminUserId },
    })
  }

  return {
    getTeam,
    getPublicTeam,
    searchTeams,
    searchOrganizationTeams,
    createTeam,
    updateTeam,
    deleteTeam,
    archiveTeam,
    unarchiveTeam,
    restoreTeam,
    getOrganizations,
    transferOwnership,
  }
}
