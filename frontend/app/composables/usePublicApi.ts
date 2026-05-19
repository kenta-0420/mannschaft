import type {
  PublicOrganizationResponse,
  PublicPostDetail,
  PublicPostSummary,
  PublicTeamResponse,
  SpringPage,
} from '~/types/public'

/**
 * F19.1 公開ページ用 API クライアント composable。
 *
 * バックエンドの認証不要 公開エンドポイント
 * （`/api/v1/public/...`）にアクセスする。レート制限あり（60req/min/IP）。
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.1
 */
export function usePublicApi() {
  const api = useApi()

  /** 公開チーム詳細を取得する。404 時は ofetch FetchError がスローされる。 */
  async function fetchPublicTeam(teamId: number): Promise<PublicTeamResponse> {
    return api<PublicTeamResponse>(`/api/v1/public/teams/${teamId}`)
  }

  /** 公開組織詳細を取得する。 */
  async function fetchPublicOrganization(orgId: number): Promise<PublicOrganizationResponse> {
    return api<PublicOrganizationResponse>(`/api/v1/public/organizations/${orgId}`)
  }

  /**
   * 公開チーム投稿一覧を取得する（ページング）。
   * Phase 1 は blog_posts のみ。
   */
  async function fetchPublicTeamPosts(
    teamId: number,
    page = 0,
    size = 20,
  ): Promise<SpringPage<PublicPostSummary>> {
    const query = new URLSearchParams()
    query.set('page', String(page))
    query.set('size', String(size))
    return api<SpringPage<PublicPostSummary>>(
      `/api/v1/public/teams/${teamId}/posts?${query.toString()}`,
    )
  }

  /** 公開チーム投稿詳細を取得する（OGP 向け）。 */
  async function fetchPublicTeamPostDetail(
    teamId: number,
    postId: number,
  ): Promise<PublicPostDetail> {
    return api<PublicPostDetail>(`/api/v1/public/teams/${teamId}/posts/${postId}`)
  }

  /** 公開組織投稿一覧を取得する（ページング）。 */
  async function fetchPublicOrganizationPosts(
    orgId: number,
    page = 0,
    size = 20,
  ): Promise<SpringPage<PublicPostSummary>> {
    const query = new URLSearchParams()
    query.set('page', String(page))
    query.set('size', String(size))
    return api<SpringPage<PublicPostSummary>>(
      `/api/v1/public/organizations/${orgId}/posts?${query.toString()}`,
    )
  }

  /** 公開組織投稿詳細を取得する。 */
  async function fetchPublicOrganizationPostDetail(
    orgId: number,
    postId: number,
  ): Promise<PublicPostDetail> {
    return api<PublicPostDetail>(`/api/v1/public/organizations/${orgId}/posts/${postId}`)
  }

  return {
    fetchPublicTeam,
    fetchPublicOrganization,
    fetchPublicTeamPosts,
    fetchPublicTeamPostDetail,
    fetchPublicOrganizationPosts,
    fetchPublicOrganizationPostDetail,
  }
}
