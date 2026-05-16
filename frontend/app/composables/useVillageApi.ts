import type {
  CreationRequestListParams,
  DailyThreadListResponse,
  DailyThreadResponse,
  JoinRequestCreateRequest,
  JoinRequestResponse,
  JoinRequestReviewRequest,
  LobbyChannelResponse,
  MembershipBanRequest,
  MembershipJoinRequest,
  MembershipListParams,
  MembershipListResponse,
  MembershipResponse,
  PinListResponse,
  PinOrderUpdateRequest,
  PinResponse,
  PostingIdentityListResponse,
  ReportCreateRequest,
  ReportListParams,
  ReportResolveRequest,
  ReportResponse,
  RoleChangeRequest,
  VillageCalendarEventCreateRequest,
  VillageCalendarEventListParams,
  VillageCalendarEventResponse,
  VillageCalendarEventUpdateRequest,
  VillageCreateRequest,
  VillageCreationRequestCreateRequest,
  VillageCreationRequestResponse,
  VillageCreationRequestReviewRequest,
  VillageFeedResponse,
  VillageFestivalCreateRequest,
  VillageFestivalResponse,
  VillageFestivalStatus,
  VillageFestivalUpdateRequest,
  VillageInternalSearchParams,
  VillageInternalSearchResponse,
  VillageMatchApplicationCreateRequest,
  VillageMatchApplicationResponse,
  VillageMatchApplicationReviewRequest,
  VillageMatchRecruitCreateRequest,
  VillageMatchRecruitListParams,
  VillageMatchRecruitResponse,
  VillageMatchRecruitUpdateRequest,
  VillageMeetupCandidateDateAddRequest,
  VillageMeetupCreateRequest,
  VillageMeetupListParams,
  VillageMeetupResponse,
  VillageMeetupUpdateRequest,
  VillageMeetupVoteRequest,
  VillageMeetupVoteSummary,
  VillageNewsletterOptOutResponse,
  VillageNewsletterSettingsRequest,
  VillageNewsletterSettingsResponse,
  VillageNicknameResponse,
  VillageNicknameUpdateRequest,
  VillagePilgrimageRecommendationResponse,
  VillagePilgrimageVisitRecordRequest,
  VillagePilgrimageVisitResponse,
  VillageRepresentativeGrantRequest,
  VillageRepresentativeResponse,
  VillageRepresentativeRevokeRequest,
  VillageRequestStatus,
  VillageResponse,
  VillageSearchParams,
  VillageSearchResponse,
  VillageSerendipityRankingResponse,
  VillageSerendipityScoreResponse,
  VillageUpdateRequest,
  VillageChronicleListResponse,
  VillageChronicleResponse,
} from '~/types/village'

/**
 * F17.1 村機能 API composable
 *
 * Backend Controller: backend/src/main/java/com/mannschaft/app/village/controller/*.java
 * 設計書: docs/features/F17.1_village_community.md §4
 *
 * パターン:
 *   - useApi() ベースの ofetch ラッパーを使用
 *   - レスポンスは Backend 仕様により `{ data: T }` でも素の T でもあり得るため、
 *     既存実装 (useTournamentApi) と同様に `<{ data: T }>` または `<T>` を個別指定
 *   - Backend は VillageController で純粋な DTO を返すケースが多いため、
 *     `data` ラップなしで型指定する設計
 */
export function useVillageApi() {
  const api = useApi()

  // クエリ文字列ヘルパー
  function qs(params?: object | null): string {
    if (!params) return ''
    const u = new URLSearchParams()
    for (const [k, v] of Object.entries(params as Record<string, unknown>)) {
      if (v !== undefined && v !== null && v !== '') u.set(k, String(v))
    }
    const s = u.toString()
    return s ? `?${s}` : ''
  }

  // =====================================================================
  // 村本体 (VillageController) — /api/v1/villages
  // =====================================================================

  /** §4.2 村検索 */
  async function searchVillages(params?: VillageSearchParams) {
    return api<VillageSearchResponse>(`/api/v1/villages/search${qs(params)}`)
  }

  /** §4.1.2 村詳細 */
  async function getVillage(villageId: string) {
    return api<VillageResponse>(`/api/v1/villages/${villageId}`)
  }

  /** §4.1.1 村作成（運営/承認自動経路） */
  async function createVillage(body: VillageCreateRequest) {
    return api<VillageResponse>('/api/v1/villages', { method: 'POST', body })
  }

  /** §4.1.3 村更新 */
  async function updateVillage(villageId: string, body: VillageUpdateRequest) {
    return api<VillageResponse>(`/api/v1/villages/${villageId}`, { method: 'PATCH', body })
  }

  /** §4.1.4 村削除（論理削除） */
  async function deleteVillage(villageId: string) {
    return api(`/api/v1/villages/${villageId}`, { method: 'DELETE' })
  }

  /** §4.1.5 村凍結 */
  async function archiveVillage(villageId: string) {
    return api<VillageResponse>(`/api/v1/villages/${villageId}/archive`, { method: 'POST' })
  }

  // =====================================================================
  // メンバーシップ (VillageMembershipController)
  // /api/v1/villages/{villageId}/memberships
  // =====================================================================

  /** §4.4.1 参加 */
  async function joinVillage(villageId: string, body: MembershipJoinRequest) {
    return api<MembershipResponse>(`/api/v1/villages/${villageId}/memberships`, {
      method: 'POST',
      body,
    })
  }

  /** §4.4.2 退村 */
  async function leaveVillage(villageId: string, membershipId: string) {
    return api(`/api/v1/villages/${villageId}/memberships/${membershipId}`, { method: 'DELETE' })
  }

  /** §4.4.3 メンバー一覧 */
  async function listMembers(villageId: string, params?: MembershipListParams) {
    return api<MembershipListResponse>(
      `/api/v1/villages/${villageId}/memberships${qs(params)}`,
    )
  }

  /** §4.4.4 ロール変更 */
  async function changeRole(villageId: string, membershipId: string, body: RoleChangeRequest) {
    return api<MembershipResponse>(
      `/api/v1/villages/${villageId}/memberships/${membershipId}/role`,
      { method: 'PATCH', body },
    )
  }

  /** §4.4.5 BAN */
  async function banMember(villageId: string, membershipId: string, body: MembershipBanRequest) {
    return api<MembershipResponse>(
      `/api/v1/villages/${villageId}/memberships/${membershipId}/ban`,
      { method: 'POST', body },
    )
  }

  // =====================================================================
  // 村作成申請 (VillageCreationRequestController)
  // =====================================================================

  /** §4.6.1 申請作成 */
  async function createCreationRequest(body: VillageCreationRequestCreateRequest) {
    return api<VillageCreationRequestResponse>('/api/v1/villages/creation-requests', {
      method: 'POST',
      body,
    })
  }

  /** §4.6.2 自分の申請一覧 */
  async function listMyCreationRequests() {
    return api<VillageCreationRequestResponse[]>('/api/v1/me/village-creation-requests')
  }

  /** §4.6.3 管理者向け一覧 */
  async function listAdminCreationRequests(params?: CreationRequestListParams) {
    return api<VillageCreationRequestResponse[]>(
      `/api/v1/admin/village-creation-requests${qs(params)}`,
    )
  }

  /** §4.6.4 承認 */
  async function approveCreationRequest(id: string, body: VillageCreationRequestReviewRequest) {
    return api<VillageCreationRequestResponse>(
      `/api/v1/admin/village-creation-requests/${id}/approve`,
      { method: 'POST', body },
    )
  }

  /** §4.6.5 却下 */
  async function rejectCreationRequest(id: string, body: VillageCreationRequestReviewRequest) {
    return api<VillageCreationRequestResponse>(
      `/api/v1/admin/village-creation-requests/${id}/reject`,
      { method: 'POST', body },
    )
  }

  /** §4.6.6 取下げ */
  async function withdrawCreationRequest(id: string) {
    return api<VillageCreationRequestResponse>(
      `/api/v1/admin/village-creation-requests/${id}/withdraw`,
      { method: 'POST' },
    )
  }

  /**
   * §4.6 統合 reviewer ヘルパ。
   * action ごとに対応エンドポイントへルーティングする。
   */
  async function reviewCreationRequest(
    id: string,
    action: 'approve' | 'reject' | 'withdraw',
    body: VillageCreationRequestReviewRequest = {},
  ) {
    switch (action) {
      case 'approve':
        return approveCreationRequest(id, body)
      case 'reject':
        return rejectCreationRequest(id, body)
      case 'withdraw':
        return withdrawCreationRequest(id)
    }
  }

  // =====================================================================
  // 参加申請 (VillageJoinRequestController)
  // /api/v1/villages/{villageId}/join-requests
  // =====================================================================

  /** §4.5.1 参加申請作成 */
  async function createJoinRequest(villageId: string, body: JoinRequestCreateRequest) {
    return api<JoinRequestResponse>(`/api/v1/villages/${villageId}/join-requests`, {
      method: 'POST',
      body,
    })
  }

  /** §4.5.2 申請一覧（村長/長老向け） */
  async function listJoinRequests(villageId: string, status?: VillageRequestStatus) {
    return api<JoinRequestResponse[]>(
      `/api/v1/villages/${villageId}/join-requests${qs({ status })}`,
    )
  }

  /** §4.5.3 承認 */
  async function approveJoinRequest(
    villageId: string,
    id: string,
    body: JoinRequestReviewRequest,
  ) {
    return api<JoinRequestResponse>(
      `/api/v1/villages/${villageId}/join-requests/${id}/approve`,
      { method: 'POST', body },
    )
  }

  /** §4.5.4 却下 */
  async function rejectJoinRequest(
    villageId: string,
    id: string,
    body: JoinRequestReviewRequest,
  ) {
    return api<JoinRequestResponse>(
      `/api/v1/villages/${villageId}/join-requests/${id}/reject`,
      { method: 'POST', body },
    )
  }

  /** §4.5.5 取下げ */
  async function withdrawJoinRequest(villageId: string, id: string) {
    return api<JoinRequestResponse>(
      `/api/v1/villages/${villageId}/join-requests/${id}/withdraw`,
      { method: 'POST' },
    )
  }

  /** §4.5 統合 reviewer ヘルパ */
  async function reviewJoinRequest(
    villageId: string,
    id: string,
    action: 'approve' | 'reject' | 'withdraw',
    body: JoinRequestReviewRequest = {},
  ) {
    switch (action) {
      case 'approve':
        return approveJoinRequest(villageId, id, body)
      case 'reject':
        return rejectJoinRequest(villageId, id, body)
      case 'withdraw':
        return withdrawJoinRequest(villageId, id)
    }
  }

  // =====================================================================
  // ニックネーム (VillageNicknameController) — /api/v1/me/village-nickname
  // =====================================================================

  /** §4.7.1 取得 */
  async function getMyNickname() {
    return api<VillageNicknameResponse>('/api/v1/me/village-nickname')
  }

  /** §4.7.2 更新 */
  async function updateNickname(body: VillageNicknameUpdateRequest) {
    return api<VillageNicknameResponse>('/api/v1/me/village-nickname', {
      method: 'PUT',
      body,
    })
  }

  // =====================================================================
  // 投稿主体 (PostingIdentityController)
  // /api/v1/me/villages/{villageId}/posting-identities
  // =====================================================================

  /** §4.8 投稿可能な主体一覧 */
  async function listPostingIdentities(villageId: string) {
    return api<PostingIdentityListResponse>(
      `/api/v1/me/villages/${villageId}/posting-identities`,
    )
  }

  // =====================================================================
  // ピン (VillagePinController) — /api/v1/me/village-pins
  // =====================================================================

  /** §4.9.1 一覧 */
  async function listPins() {
    return api<PinListResponse>('/api/v1/me/village-pins')
  }

  /** §4.9.2 追加 */
  async function addPin(villageId: string) {
    return api<PinResponse>(`/api/v1/me/village-pins/${villageId}`, { method: 'POST' })
  }

  /** §4.9.3 解除 */
  async function removePin(villageId: string) {
    return api(`/api/v1/me/village-pins/${villageId}`, { method: 'DELETE' })
  }

  /** §4.9.4 並び替え */
  async function updatePinOrder(body: PinOrderUpdateRequest) {
    return api<PinListResponse>('/api/v1/me/village-pins/order', { method: 'PATCH', body })
  }

  // =====================================================================
  // ロビー (VillageLobbyController) — /api/v1/villages/{villageId}/lobby
  // =====================================================================

  /** §4.10.1 ロビーチャネル取得 */
  async function getLobbyChannel(villageId: string) {
    return api<LobbyChannelResponse>(`/api/v1/villages/${villageId}/lobby`)
  }

  /** §4.10.2 日次スレッド一覧 */
  async function listDailyThreads(villageId: string, days?: number) {
    return api<DailyThreadListResponse>(
      `/api/v1/villages/${villageId}/lobby/daily${qs({ days })}`,
    )
  }

  /** §4.10.3 指定日の日次スレッド */
  async function getDailyThread(villageId: string, date: string) {
    return api<DailyThreadResponse>(`/api/v1/villages/${villageId}/lobby/daily/${date}`)
  }

  // =====================================================================
  // 通報 (VillageReportController) — /api/v1/villages/{villageId}/reports
  // =====================================================================

  /** §4.11.1 通報送信 */
  async function createReport(villageId: string, body: ReportCreateRequest) {
    return api<ReportResponse>(`/api/v1/villages/${villageId}/reports`, {
      method: 'POST',
      body,
    })
  }

  /** §4.11.2 通報一覧（村長/長老/運営向け） */
  async function listReports(villageId: string, params?: ReportListParams) {
    return api<ReportResponse[]>(
      `/api/v1/villages/${villageId}/reports${qs(params)}`,
    )
  }

  /** §4.11.3 通報解決 */
  async function resolveReport(villageId: string, reportId: string, body: ReportResolveRequest) {
    return api<ReportResponse>(
      `/api/v1/villages/${villageId}/reports/${reportId}/resolve`,
      { method: 'POST', body },
    )
  }

  // =====================================================================
  // 村内検索 (VillageSearchController)
  // =====================================================================

  /** §4.13 村内検索（投稿/メッセージ/メンバー） */
  async function searchVillageInternal(villageId: string, params: VillageInternalSearchParams) {
    return api<VillageInternalSearchResponse>(
      `/api/v1/villages/${villageId}/search${qs(params)}`,
    )
  }

  // =====================================================================
  // 横断フィード (VillageFeedController) — /api/v1/me/village-feed
  // =====================================================================

  /** §4.12 自分の横断フィード（ピン村サマリ同梱） */
  async function getFeed() {
    return api<VillageFeedResponse>('/api/v1/me/village-feed')
  }

  // =====================================================================
  // Phase 2: 代表委任 (VillageRepresentativeController)
  // /api/v1/villages/{villageId}/representatives
  //
  // 注意: Phase 2 の Backend Controller は未実装。
  // ここでの URL は設計書 §3.11 / §13.2 に基づく推測であり、
  // Backend 完成後に微調整する可能性がある。
  // =====================================================================

  /** 代表委任一覧 */
  async function listRepresentatives(villageId: string) {
    return api<VillageRepresentativeResponse[]>(
      `/api/v1/villages/${villageId}/representatives`,
    )
  }

  /** 代表委任を発行 */
  async function grantRepresentative(
    villageId: string,
    body: VillageRepresentativeGrantRequest,
  ) {
    return api<VillageRepresentativeResponse>(
      `/api/v1/villages/${villageId}/representatives`,
      { method: 'POST', body },
    )
  }

  /** 代表委任を取消 */
  async function revokeRepresentative(
    villageId: string,
    id: string,
    body: VillageRepresentativeRevokeRequest,
  ) {
    return api<VillageRepresentativeResponse>(
      `/api/v1/villages/${villageId}/representatives/${id}/revoke`,
      { method: 'POST', body },
    )
  }

  // =====================================================================
  // Phase 2: 歳時記カレンダー (VillageCalendarEventController)
  // /api/v1/villages/{villageId}/calendar-events
  // =====================================================================

  /** 歳時記カレンダー一覧 */
  async function listCalendarEvents(
    villageId: string,
    params?: VillageCalendarEventListParams,
  ) {
    return api<VillageCalendarEventResponse[]>(
      `/api/v1/villages/${villageId}/calendar-events${qs(params)}`,
    )
  }

  /** 歳時記カレンダー詳細 */
  async function getCalendarEvent(villageId: string, id: string) {
    return api<VillageCalendarEventResponse>(
      `/api/v1/villages/${villageId}/calendar-events/${id}`,
    )
  }

  /** 歳時記カレンダー作成 */
  async function createCalendarEvent(
    villageId: string,
    body: VillageCalendarEventCreateRequest,
  ) {
    return api<VillageCalendarEventResponse>(
      `/api/v1/villages/${villageId}/calendar-events`,
      { method: 'POST', body },
    )
  }

  /** 歳時記カレンダー更新 */
  async function updateCalendarEvent(
    villageId: string,
    id: string,
    body: VillageCalendarEventUpdateRequest,
  ) {
    return api<VillageCalendarEventResponse>(
      `/api/v1/villages/${villageId}/calendar-events/${id}`,
      { method: 'PATCH', body },
    )
  }

  /** 歳時記カレンダー削除 */
  async function deleteCalendarEvent(villageId: string, id: string) {
    return api(`/api/v1/villages/${villageId}/calendar-events/${id}`, {
      method: 'DELETE',
    })
  }

  // =====================================================================
  // Phase 2: お祭り (VillageFestivalController)
  // /api/v1/villages/{villageId}/festivals
  // =====================================================================

  /** お祭り一覧 */
  async function listFestivals(villageId: string, status?: VillageFestivalStatus) {
    return api<VillageFestivalResponse[]>(
      `/api/v1/villages/${villageId}/festivals${qs({ status })}`,
    )
  }

  /** お祭り詳細 */
  async function getFestival(villageId: string, id: string) {
    return api<VillageFestivalResponse>(
      `/api/v1/villages/${villageId}/festivals/${id}`,
    )
  }

  /** お祭り作成 */
  async function createFestival(
    villageId: string,
    body: VillageFestivalCreateRequest,
  ) {
    return api<VillageFestivalResponse>(
      `/api/v1/villages/${villageId}/festivals`,
      { method: 'POST', body },
    )
  }

  /** お祭り更新 */
  async function updateFestival(
    villageId: string,
    id: string,
    body: VillageFestivalUpdateRequest,
  ) {
    return api<VillageFestivalResponse>(
      `/api/v1/villages/${villageId}/festivals/${id}`,
      { method: 'PATCH', body },
    )
  }

  /** お祭り中止 */
  async function cancelFestival(villageId: string, id: string) {
    return api<VillageFestivalResponse>(
      `/api/v1/villages/${villageId}/festivals/${id}/cancel`,
      { method: 'POST' },
    )
  }

  // =====================================================================
  // Phase 2: 練習試合募集 (VillageMatchRecruitController)
  // /api/v1/villages/{villageId}/match-recruits
  // =====================================================================

  /** 練習試合募集一覧 */
  async function listMatchRecruits(
    villageId: string,
    params?: VillageMatchRecruitListParams,
  ) {
    return api<VillageMatchRecruitResponse[]>(
      `/api/v1/villages/${villageId}/match-recruits${qs(params)}`,
    )
  }

  /** 練習試合募集詳細 */
  async function getMatchRecruit(villageId: string, id: string) {
    return api<VillageMatchRecruitResponse>(
      `/api/v1/villages/${villageId}/match-recruits/${id}`,
    )
  }

  /** 練習試合募集作成 */
  async function createMatchRecruit(
    villageId: string,
    body: VillageMatchRecruitCreateRequest,
  ) {
    return api<VillageMatchRecruitResponse>(
      `/api/v1/villages/${villageId}/match-recruits`,
      { method: 'POST', body },
    )
  }

  /** 練習試合募集更新 */
  async function updateMatchRecruit(
    villageId: string,
    id: string,
    body: VillageMatchRecruitUpdateRequest,
  ) {
    return api<VillageMatchRecruitResponse>(
      `/api/v1/villages/${villageId}/match-recruits/${id}`,
      { method: 'PATCH', body },
    )
  }

  /** 練習試合募集を締切（CLOSED 化） */
  async function closeMatchRecruit(villageId: string, id: string) {
    return api<VillageMatchRecruitResponse>(
      `/api/v1/villages/${villageId}/match-recruits/${id}/close`,
      { method: 'POST' },
    )
  }

  // =====================================================================
  // Phase 2: 練習試合応募 (VillageMatchApplicationController)
  // /api/v1/villages/{villageId}/match-recruits/{recruitId}/applications
  // =====================================================================

  /** 応募する */
  async function applyToMatchRecruit(
    villageId: string,
    recruitId: string,
    body: VillageMatchApplicationCreateRequest,
  ) {
    return api<VillageMatchApplicationResponse>(
      `/api/v1/villages/${villageId}/match-recruits/${recruitId}/applications`,
      { method: 'POST', body },
    )
  }

  /** 応募一覧（募集主向け） */
  async function listApplications(villageId: string, recruitId: string) {
    return api<VillageMatchApplicationResponse[]>(
      `/api/v1/villages/${villageId}/match-recruits/${recruitId}/applications`,
    )
  }

  /** 応募を審査（ACCEPT/REJECT） */
  async function reviewApplication(
    villageId: string,
    recruitId: string,
    applicationId: string,
    body: VillageMatchApplicationReviewRequest & { action: 'accept' | 'reject' },
  ) {
    const { action, ...reviewBody } = body
    return api<VillageMatchApplicationResponse>(
      `/api/v1/villages/${villageId}/match-recruits/${recruitId}/applications/${applicationId}/${action}`,
      { method: 'POST', body: reviewBody },
    )
  }

  /** 応募を取り下げる */
  async function withdrawApplication(
    villageId: string,
    recruitId: string,
    applicationId: string,
  ) {
    return api<VillageMatchApplicationResponse>(
      `/api/v1/villages/${villageId}/match-recruits/${recruitId}/applications/${applicationId}/withdraw`,
      { method: 'POST' },
    )
  }

  // =====================================================================
  // Phase 2: 村紋アップロード (VillageMonshoController)
  // /api/v1/villages/{villageId}/monsho
  //
  // 設計書 §13.2: villages.monsho_r2_key 用。
  // multipart/form-data で画像をアップロードし、R2 キーを村本体に紐付ける。
  // =====================================================================

  /** 村紋画像をアップロード（multipart/form-data） */
  async function uploadMonsho(villageId: string, file: File) {
    const form = new FormData()
    form.append('file', file)
    return api<VillageResponse>(
      `/api/v1/villages/${villageId}/monsho`,
      { method: 'POST', body: form },
    )
  }

  // =====================================================================
  // F17 Phase 3 — 寄合 (VillageMeetupController)
  // /api/v1/villages/{villageId}/meetups
  // =====================================================================

  async function createMeetup(villageId: string, body: VillageMeetupCreateRequest) {
    return api<VillageMeetupResponse>(
      `/api/v1/villages/${villageId}/meetups`,
      { method: 'POST', body },
    )
  }

  async function listMeetups(villageId: string, params?: VillageMeetupListParams) {
    return api<VillageMeetupResponse[]>(
      `/api/v1/villages/${villageId}/meetups${qs(params)}`,
    )
  }

  async function getMeetup(villageId: string, meetupId: string) {
    return api<VillageMeetupResponse>(
      `/api/v1/villages/${villageId}/meetups/${meetupId}`,
    )
  }

  async function updateMeetup(
    villageId: string,
    meetupId: string,
    body: VillageMeetupUpdateRequest,
  ) {
    return api<VillageMeetupResponse>(
      `/api/v1/villages/${villageId}/meetups/${meetupId}`,
      { method: 'PATCH', body },
    )
  }

  async function confirmMeetup(
    villageId: string,
    meetupId: string,
    candidateDateId: string,
  ) {
    return api<VillageMeetupResponse>(
      `/api/v1/villages/${villageId}/meetups/${meetupId}/confirm`,
      { method: 'POST', body: { candidateDateId } },
    )
  }

  async function cancelMeetup(villageId: string, meetupId: string) {
    return api<VillageMeetupResponse>(
      `/api/v1/villages/${villageId}/meetups/${meetupId}/cancel`,
      { method: 'POST' },
    )
  }

  async function addCandidateDate(
    villageId: string,
    meetupId: string,
    body: VillageMeetupCandidateDateAddRequest,
  ) {
    return api<VillageMeetupResponse>(
      `/api/v1/villages/${villageId}/meetups/${meetupId}/candidate-dates`,
      { method: 'POST', body },
    )
  }

  async function removeCandidateDate(
    villageId: string,
    meetupId: string,
    candidateDateId: string,
  ) {
    return api<VillageMeetupResponse>(
      `/api/v1/villages/${villageId}/meetups/${meetupId}/candidate-dates/${candidateDateId}`,
      { method: 'DELETE' },
    )
  }

  async function castVote(
    villageId: string,
    meetupId: string,
    body: VillageMeetupVoteRequest,
  ) {
    return api<VillageMeetupResponse>(
      `/api/v1/villages/${villageId}/meetups/${meetupId}/votes`,
      { method: 'POST', body },
    )
  }

  async function getVoteSummary(villageId: string, meetupId: string) {
    return api<VillageMeetupVoteSummary>(
      `/api/v1/villages/${villageId}/meetups/${meetupId}/votes/summary`,
    )
  }

  // =====================================================================
  // F17 Phase 3 — 村史 (VillageChronicleController)
  // /api/v1/villages/{villageId}/chronicles
  // =====================================================================

  async function listChronicles(villageId: string, page?: number, size?: number) {
    return api<VillageChronicleListResponse>(
      `/api/v1/villages/${villageId}/chronicles${qs({ page, size })}`,
    )
  }

  async function getChronicle(villageId: string, chronicleId: string) {
    return api<VillageChronicleResponse>(
      `/api/v1/villages/${villageId}/chronicles/${chronicleId}`,
    )
  }

  // =====================================================================
  // F17 Phase 3 — ご縁スコア (VillageSerendipityController)
  // /api/v1/villages/{villageId}/serendipity
  // =====================================================================

  async function getSerendipityRanking(
    villageId: string,
    page?: number,
    size?: number,
  ) {
    return api<VillageSerendipityRankingResponse>(
      `/api/v1/villages/${villageId}/serendipity/ranking${qs({ page, size })}`,
    )
  }

  async function getMyScore(villageId: string) {
    return api<VillageSerendipityScoreResponse>(
      `/api/v1/villages/${villageId}/serendipity/me`,
    )
  }

  // =====================================================================
  // F17 Phase 3 — 巡礼 (VillagePilgrimageController)
  // /api/v1/pilgrimage
  // =====================================================================

  async function getTodaysPilgrimage() {
    return api<VillagePilgrimageRecommendationResponse>(
      '/api/v1/pilgrimage/today',
    )
  }

  async function recordVisit(body: VillagePilgrimageVisitRecordRequest) {
    return api<VillagePilgrimageVisitResponse>(
      '/api/v1/pilgrimage/visits',
      { method: 'POST', body },
    )
  }

  async function listMyVisits(page?: number, size?: number) {
    return api<VillagePilgrimageVisitResponse[]>(
      `/api/v1/pilgrimage/visits${qs({ page, size })}`,
    )
  }

  // =====================================================================
  // F17 Phase 3 — ニュースレター (VillageNewsletterController)
  // /api/v1/villages/newsletter
  // =====================================================================

  async function getNewsletterSettings() {
    return api<VillageNewsletterSettingsResponse>(
      '/api/v1/villages/newsletter/settings',
    )
  }

  async function updateNewsletterSettings(body: VillageNewsletterSettingsRequest) {
    return api<VillageNewsletterSettingsResponse>(
      '/api/v1/villages/newsletter/settings',
      { method: 'PUT', body },
    )
  }

  async function optOut() {
    return api<VillageNewsletterOptOutResponse>(
      '/api/v1/villages/newsletter/opt-out',
      { method: 'POST' },
    )
  }

  async function optIn() {
    return api<VillageNewsletterOptOutResponse>(
      '/api/v1/villages/newsletter/opt-in',
      { method: 'POST' },
    )
  }

  return {
    // 村本体
    searchVillages,
    getVillage,
    createVillage,
    updateVillage,
    deleteVillage,
    archiveVillage,
    // メンバーシップ
    joinVillage,
    leaveVillage,
    listMembers,
    changeRole,
    banMember,
    // 村作成申請
    createCreationRequest,
    listMyCreationRequests,
    listAdminCreationRequests,
    approveCreationRequest,
    rejectCreationRequest,
    withdrawCreationRequest,
    reviewCreationRequest,
    // 参加申請
    createJoinRequest,
    listJoinRequests,
    approveJoinRequest,
    rejectJoinRequest,
    withdrawJoinRequest,
    reviewJoinRequest,
    // ニックネーム
    getMyNickname,
    updateNickname,
    // 投稿主体
    listPostingIdentities,
    // ピン
    listPins,
    addPin,
    removePin,
    updatePinOrder,
    // ロビー
    getLobbyChannel,
    listDailyThreads,
    getDailyThread,
    // 通報
    createReport,
    listReports,
    resolveReport,
    // 村内検索
    searchVillageInternal,
    // 横断フィード
    getFeed,
    // Phase 2: 代表委任
    listRepresentatives,
    grantRepresentative,
    revokeRepresentative,
    // Phase 2: 歳時記カレンダー
    listCalendarEvents,
    getCalendarEvent,
    createCalendarEvent,
    updateCalendarEvent,
    deleteCalendarEvent,
    // Phase 2: お祭り
    listFestivals,
    getFestival,
    createFestival,
    updateFestival,
    cancelFestival,
    // Phase 2: 練習試合募集
    listMatchRecruits,
    getMatchRecruit,
    createMatchRecruit,
    updateMatchRecruit,
    closeMatchRecruit,
    // Phase 2: 練習試合応募
    applyToMatchRecruit,
    listApplications,
    reviewApplication,
    withdrawApplication,
    // Phase 2: 村紋
    uploadMonsho,
    // Phase 3: 寄合
    createMeetup,
    listMeetups,
    getMeetup,
    updateMeetup,
    confirmMeetup,
    cancelMeetup,
    addCandidateDate,
    removeCandidateDate,
    castVote,
    getVoteSummary,
    // Phase 3: 村史
    listChronicles,
    getChronicle,
    // Phase 3: ご縁スコア
    getSerendipityRanking,
    getMyScore,
    // Phase 3: 巡礼
    getTodaysPilgrimage,
    recordVisit,
    listMyVisits,
    // Phase 3: ニュースレター
    getNewsletterSettings,
    updateNewsletterSettings,
    optOut,
    optIn,
  }
}
