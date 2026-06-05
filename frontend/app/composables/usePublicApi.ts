import type {
  NameDisclosureChangeLogResponse,
  PublicEventResponse,
  PublicOrganizationResponse,
  PublicOrganizationSearchResult,
  PublicPostComment,
  PublicPostDetail,
  PublicPostSummary,
  PublicTeamResponse,
  PublicTeamSearchResult,
  PublicTimelinePostResponse,
  PublicUserPostSummary,
  PublicUserProfile,
  SpringPage,
  SupporterNameDisclosureResponse,
  NameDisclosureMode,
  UpdatePublicSettingsRequest,
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
  async function fetchPublicTeam(teamId: string): Promise<PublicTeamResponse> {
    return api<PublicTeamResponse>(`/api/v1/public/teams/${teamId}`)
  }

  /** 公開組織詳細を取得する。 */
  async function fetchPublicOrganization(orgId: string): Promise<PublicOrganizationResponse> {
    return api<PublicOrganizationResponse>(`/api/v1/public/organizations/${orgId}`)
  }

  /**
   * 公開チーム投稿一覧を取得する（ページング）。
   * Phase 1 は blog_posts のみ。
   */
  async function fetchPublicTeamPosts(
    teamId: string,
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
    teamId: string,
    postId: number,
  ): Promise<PublicPostDetail> {
    return api<PublicPostDetail>(`/api/v1/public/teams/${teamId}/posts/${postId}`)
  }

  /** 公開組織投稿一覧を取得する（ページング）。 */
  async function fetchPublicOrganizationPosts(
    orgId: string,
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
    orgId: string,
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

  // ─── F19.1 Phase 7: タイムライン投稿・イベント 公開 API ───

  /**
   * チームのタイムライン投稿一覧を取得する（ページング）。
   *
   * timelinePostsPublic = true のチームのみ返却される。
   * エンドポイント: GET /api/v1/public/teams/{teamId}/timeline-posts
   */
  async function fetchPublicTeamTimelinePosts(
    teamId: string,
    page = 0,
    size = 20,
  ): Promise<SpringPage<PublicTimelinePostResponse>> {
    const query = new URLSearchParams()
    query.set('page', String(page))
    query.set('size', String(size))
    return api<SpringPage<PublicTimelinePostResponse>>(
      `/api/v1/public/teams/${teamId}/timeline-posts?${query.toString()}`,
    )
  }

  /**
   * 組織のタイムライン投稿一覧を取得する（ページング）。
   *
   * エンドポイント: GET /api/v1/public/organizations/{orgId}/timeline-posts
   */
  async function fetchPublicOrgTimelinePosts(
    orgId: string,
    page = 0,
    size = 20,
  ): Promise<SpringPage<PublicTimelinePostResponse>> {
    const query = new URLSearchParams()
    query.set('page', String(page))
    query.set('size', String(size))
    return api<SpringPage<PublicTimelinePostResponse>>(
      `/api/v1/public/organizations/${orgId}/timeline-posts?${query.toString()}`,
    )
  }

  /**
   * チームのイベント一覧を取得する（ページング）。
   *
   * エンドポイント: GET /api/v1/public/teams/{teamId}/events
   */
  async function fetchPublicTeamEvents(
    teamId: string,
    page = 0,
    size = 20,
  ): Promise<SpringPage<PublicEventResponse>> {
    const query = new URLSearchParams()
    query.set('page', String(page))
    query.set('size', String(size))
    return api<SpringPage<PublicEventResponse>>(
      `/api/v1/public/teams/${teamId}/events?${query.toString()}`,
    )
  }

  /**
   * 組織のイベント一覧を取得する（ページング）。
   *
   * エンドポイント: GET /api/v1/public/organizations/{orgId}/events
   */
  async function fetchPublicOrgEvents(
    orgId: string,
    page = 0,
    size = 20,
  ): Promise<SpringPage<PublicEventResponse>> {
    const query = new URLSearchParams()
    query.set('page', String(page))
    query.set('size', String(size))
    return api<SpringPage<PublicEventResponse>>(
      `/api/v1/public/organizations/${orgId}/events?${query.toString()}`,
    )
  }

  // ─── F19.1 Phase 7: Admin 向け 公開設定 PATCH API ───

  /**
   * チームの公開設定を更新する（ADMIN / SYSTEM_ADMIN 限定）。
   *
   * エンドポイント: PATCH /api/v1/admin/teams/{teamId}/public-settings
   */
  async function updateTeamPublicSettings(
    teamId: string,
    req: UpdatePublicSettingsRequest,
  ): Promise<void> {
    await api(`/api/v1/admin/teams/${teamId}/public-settings`, {
      method: 'PATCH',
      body: req,
    })
  }

  /**
   * 組織の公開設定を更新する（ADMIN / SYSTEM_ADMIN 限定）。
   *
   * エンドポイント: PATCH /api/v1/admin/organizations/{orgId}/public-settings
   */
  async function updateOrgPublicSettings(
    orgId: string,
    req: UpdatePublicSettingsRequest,
  ): Promise<void> {
    await api(`/api/v1/admin/organizations/${orgId}/public-settings`, {
      method: 'PATCH',
      body: req,
    })
  }

  // ─── F19.1 Phase 6-B: 公開投稿コメント API ───

  /**
   * 公開投稿のコメント一覧を取得する（ページング）。
   *
   * 未ログインでも閲覧可能。
   * エンドポイント: GET /api/v1/public/blog-posts/{postId}/comments
   */
  async function fetchPostComments(
    postId: number | string,
    page = 0,
    size = 20,
  ): Promise<SpringPage<PublicPostComment>> {
    const query = new URLSearchParams()
    query.set('page', String(page))
    query.set('size', String(size))
    return api<SpringPage<PublicPostComment>>(
      `/api/v1/public/blog-posts/${postId}/comments?${query.toString()}`,
    )
  }

  /**
   * 公開投稿にコメントを投稿する。
   *
   * 認証必須。
   * エンドポイント: POST /api/v1/public/blog-posts/{postId}/comments
   */
  async function postComment(postId: number | string, content: string): Promise<PublicPostComment> {
    return api<PublicPostComment>(`/api/v1/public/blog-posts/${postId}/comments`, {
      method: 'POST',
      body: { content },
    })
  }

  /**
   * 公開投稿のコメントを削除する。
   *
   * 認証必須（作者 or ADMIN）。
   * エンドポイント: DELETE /api/v1/public/blog-posts/{postId}/comments/{commentId}
   */
  async function deleteComment(
    postId: number | string,
    commentId: string,
  ): Promise<void> {
    await api(`/api/v1/public/blog-posts/${postId}/comments/${commentId}`, {
      method: 'DELETE',
    })
  }

  // ─── F19.1 Phase 6: 個人プロフィール公開 API ───

  /**
   * 公開ユーザープロフィールを取得する。
   *
   * {@code public_profile_enabled = true} のユーザーのみ 200 を返す。
   * 不在 / 非公開 / 削除済みは一律 404（IDOR 対策）。
   * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.6 Phase 6
   */
  async function fetchPublicUserProfile(userId: number | string): Promise<PublicUserProfile> {
    return api<PublicUserProfile>(`/api/v1/public/users/${userId}`)
  }

  /**
   * 公開ユーザーの投稿一覧を取得する（ページング）。
   *
   * visibility=PUBLIC かつ status=PUBLISHED かつ public_visible=true の投稿のみ。
   * ユーザー自体が非公開の場合は 404（IDOR 対策）。
   */
  async function fetchPublicUserPosts(
    userId: number | string,
    page = 0,
    size = 20,
  ): Promise<SpringPage<PublicUserPostSummary>> {
    const query = new URLSearchParams()
    query.set('page', String(page))
    query.set('size', String(size))
    return api<SpringPage<PublicUserPostSummary>>(
      `/api/v1/public/users/${userId}/posts?${query.toString()}`,
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
    teamId: string,
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
    organizationId: string,
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
    teamId: string,
  ): Promise<NameDisclosureChangeLogResponse[]> {
    return api<NameDisclosureChangeLogResponse[]>(
      `/api/v1/admin/teams/${teamId}/supporter-name-disclosure/history`,
    )
  }

  /**
   * 組織の投稿者識別モード変更履歴を取得する（ADMIN / SYSTEM_ADMIN 限定）。
   */
  async function fetchOrganizationNameDisclosureHistory(
    organizationId: string,
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
    fetchPostComments,
    postComment,
    deleteComment,
    fetchPublicUserProfile,
    fetchPublicUserPosts,
    patchTeamNameDisclosure,
    patchOrganizationNameDisclosure,
    fetchTeamNameDisclosureHistory,
    fetchOrganizationNameDisclosureHistory,
    // Phase 7
    fetchPublicTeamTimelinePosts,
    fetchPublicOrgTimelinePosts,
    fetchPublicTeamEvents,
    fetchPublicOrgEvents,
    updateTeamPublicSettings,
    updateOrgPublicSettings,
  }
}
