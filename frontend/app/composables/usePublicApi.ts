import type {
  NameDisclosureChangeLogResponse,
  PublicOrganizationResponse,
  PublicOrganizationSearchResult,
  PublicPostDetail,
  PublicPostSummary,
  PublicTeamResponse,
  PublicTeamSearchResult,
  SpringPage,
  SupporterNameDisclosureResponse,
  NameDisclosureMode,
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

  // ─── F19.1 Phase 4: 公開チーム・組織検索 API ───

  /**
   * 公開チームを検索する（ページング）。
   *
   * @param params.keyword チーム名キーワード（部分一致）
   * @param params.prefecture 都道府県名（完全一致）
   * @param params.page ページ番号（0 始まり）
   * @param params.size 1 ページあたりの件数
   */
  async function searchPublicTeams(params: {
    keyword?: string
    prefecture?: string
    page?: number
    size?: number
  }): Promise<SpringPage<PublicTeamSearchResult>> {
    const query = new URLSearchParams()
    if (params.keyword) query.set('keyword', params.keyword)
    if (params.prefecture) query.set('prefecture', params.prefecture)
    if (params.page !== undefined) query.set('page', String(params.page))
    if (params.size !== undefined) query.set('size', String(params.size))
    return api<SpringPage<PublicTeamSearchResult>>(`/api/v1/public/teams/search?${query.toString()}`)
  }

  /**
   * 公開組織を検索する（ページング）。
   *
   * @param params.keyword 組織名キーワード（部分一致）
   * @param params.prefecture 都道府県名（完全一致）
   * @param params.page ページ番号（0 始まり）
   * @param params.size 1 ページあたりの件数
   */
  async function searchPublicOrganizations(params: {
    keyword?: string
    prefecture?: string
    page?: number
    size?: number
  }): Promise<SpringPage<PublicOrganizationSearchResult>> {
    const query = new URLSearchParams()
    if (params.keyword) query.set('keyword', params.keyword)
    if (params.prefecture) query.set('prefecture', params.prefecture)
    if (params.page !== undefined) query.set('page', String(params.page))
    if (params.size !== undefined) query.set('size', String(params.size))
    return api<SpringPage<PublicOrganizationSearchResult>>(
      `/api/v1/public/organizations/search?${query.toString()}`,
    )
  }

  // ─── F19.1 Phase 2: Admin 向け supporter_name_disclosure 切替 API ───

  /**
   * チームの投稿者識別モードを切り替える（ADMIN / SYSTEM_ADMIN 限定）。
   *
   * confirmed=true が必須。DISPLAY_NAME → REAL_NAME の切替時は
   * SupporterNameDisclosureWarningDialog で確認を取ってから呼び出すこと。
   */
  async function patchTeamNameDisclosure(
    teamId: number,
    mode: NameDisclosureMode,
    confirmed: boolean,
  ): Promise<SupporterNameDisclosureResponse> {
    return api<SupporterNameDisclosureResponse>(
      `/api/v1/admin/teams/${teamId}/supporter-name-disclosure`,
      {
        method: 'PATCH',
        body: { mode, confirmed },
      },
    )
  }

  /**
   * 組織の投稿者識別モードを切り替える（ADMIN / SYSTEM_ADMIN 限定）。
   */
  async function patchOrganizationNameDisclosure(
    organizationId: number,
    mode: NameDisclosureMode,
    confirmed: boolean,
  ): Promise<SupporterNameDisclosureResponse> {
    return api<SupporterNameDisclosureResponse>(
      `/api/v1/admin/organizations/${organizationId}/supporter-name-disclosure`,
      {
        method: 'PATCH',
        body: { mode, confirmed },
      },
    )
  }

  /**
   * チームの投稿者識別モード変更履歴を取得する（ADMIN / SYSTEM_ADMIN 限定）。
   */
  async function fetchTeamNameDisclosureHistory(
    teamId: number,
  ): Promise<NameDisclosureChangeLogResponse[]> {
    return api<NameDisclosureChangeLogResponse[]>(
      `/api/v1/admin/teams/${teamId}/supporter-name-disclosure/history`,
    )
  }

  /**
   * 組織の投稿者識別モード変更履歴を取得する（ADMIN / SYSTEM_ADMIN 限定）。
   */
  async function fetchOrganizationNameDisclosureHistory(
    organizationId: number,
  ): Promise<NameDisclosureChangeLogResponse[]> {
    return api<NameDisclosureChangeLogResponse[]>(
      `/api/v1/admin/organizations/${organizationId}/supporter-name-disclosure/history`,
    )
  }

  return {
    fetchPublicTeam,
    fetchPublicOrganization,
    fetchPublicTeamPosts,
    fetchPublicTeamPostDetail,
    fetchPublicOrganizationPosts,
    fetchPublicOrganizationPostDetail,
    searchPublicTeams,
    searchPublicOrganizations,
    patchTeamNameDisclosure,
    patchOrganizationNameDisclosure,
    fetchTeamNameDisclosureHistory,
    fetchOrganizationNameDisclosureHistory,
  }
}
