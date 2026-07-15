import type {
  VillageMeetupCreateRequest,
  VillageMeetupListParams,
  VillageMeetupResponse,
  VillageMeetupUpdateRequest,
  VillageMeetupCandidateDateAddRequest,
  VillageMeetupVoteRequest,
  VillageMeetupVoteSummary,
  VillageChronicleResponse,
  VillageSerendipityRankingResponse,
  VillageSerendipityScoreResponse,
  VillagePilgrimageRecommendationResponse,
  VillagePilgrimageVisitRecordRequest,
  VillagePilgrimageVisitResponse,
  VillageNewsletterSettingsResponse,
  VillageNewsletterSettingsRequest,
  VillageNewsletterOptOutResponse,
  VillageNewsletterSendLogResponse,
} from '~/types/village'

/**
 * F17.1 村機能 API composable — Phase 3 (寄合 / 村史 / ご縁スコア / 巡礼 / ニュースレター)
 *
 * Backend Controller:
 * - VillageMeetupController
 * - VillageChronicleController
 * - VillageSerendipityController
 * - VillagePilgrimageController
 * - VillageNewsletterController
 */
export function useVillagePhase3Api() {
  const api = useApi()

  function qs(params?: object | null): string {
    if (!params) return ''
    const u = new URLSearchParams()
    for (const [k, v] of Object.entries(params as Record<string, unknown>)) {
      if (v !== undefined && v !== null && v !== '') u.set(k, String(v))
    }
    const s = u.toString()
    return s ? `?${s}` : ''
  }

  // ==========================================================================
  // 寄合
  // ==========================================================================

  async function createMeetup(villageId: string, body: VillageMeetupCreateRequest) {
    const res = await api<{ data: VillageMeetupResponse }>(
      `/api/v1/villages/${villageId}/meetups`,
      { method: 'POST', body },
    )
    return res.data
  }

  async function listMeetups(villageId: string, params?: VillageMeetupListParams) {
    const res = await api<{ data: VillageMeetupResponse[] }>(
      `/api/v1/villages/${villageId}/meetups${qs(params)}`,
    )
    return res.data
  }

  async function getMeetup(villageId: string, meetupId: string) {
    const res = await api<{ data: VillageMeetupResponse }>(
      `/api/v1/villages/${villageId}/meetups/${meetupId}`,
    )
    return res.data
  }

  async function updateMeetup(
    villageId: string,
    meetupId: string,
    body: VillageMeetupUpdateRequest,
  ) {
    const res = await api<{ data: VillageMeetupResponse }>(
      `/api/v1/villages/${villageId}/meetups/${meetupId}`,
      { method: 'PATCH', body },
    )
    return res.data
  }

  async function confirmMeetup(
    villageId: string,
    meetupId: string,
    candidateDateId: string,
  ) {
    const res = await api<{ data: VillageMeetupResponse }>(
      `/api/v1/villages/${villageId}/meetups/${meetupId}/confirm`,
      { method: 'POST', body: { candidateDateId } },
    )
    return res.data
  }

  async function cancelMeetup(villageId: string, meetupId: string) {
    const res = await api<{ data: VillageMeetupResponse }>(
      `/api/v1/villages/${villageId}/meetups/${meetupId}/cancel`,
      { method: 'POST' },
    )
    return res.data
  }

  async function addCandidateDate(
    villageId: string,
    meetupId: string,
    body: VillageMeetupCandidateDateAddRequest,
  ) {
    const res = await api<{ data: VillageMeetupResponse }>(
      `/api/v1/villages/${villageId}/meetups/${meetupId}/candidate-dates`,
      { method: 'POST', body },
    )
    return res.data
  }

  async function removeCandidateDate(
    villageId: string,
    meetupId: string,
    candidateDateId: string,
  ) {
    const res = await api<{ data: VillageMeetupResponse }>(
      `/api/v1/villages/${villageId}/meetups/${meetupId}/candidate-dates/${candidateDateId}`,
      { method: 'DELETE' },
    )
    return res.data
  }

  async function castVote(
    villageId: string,
    meetupId: string,
    body: VillageMeetupVoteRequest,
  ) {
    const res = await api<{ data: VillageMeetupResponse }>(
      `/api/v1/villages/${villageId}/meetups/${meetupId}/votes`,
      { method: 'POST', body },
    )
    return res.data
  }

  async function getVoteSummary(villageId: string, meetupId: string) {
    const res = await api<{ data: VillageMeetupVoteSummary }>(
      `/api/v1/villages/${villageId}/meetups/${meetupId}/votes/summary`,
    )
    return res.data
  }

  // ==========================================================================
  // 村史
  // ==========================================================================

  /**
   * 村史一覧（年月降順）を取得する。
   *
   * BE は `ApiResponse<List<ChronicleResponse>>` を返す（`{ items, total }` の
   * エンベロープではなく素の配列）。ページングも未対応のため引数は villageId のみ。
   */
  async function listChronicles(villageId: string) {
    const res = await api<{ data: VillageChronicleResponse[] }>(
      `/api/v1/villages/${villageId}/chronicles`,
    )
    return res.data
  }

  /**
   * 指定月の村史を取得する。
   *
   * @param yearMonth ISO `YYYY-MM-DD` 形式（BE は `LocalDate` を受ける）。
   *                  村史の ID（UUID）ではないので注意。
   */
  async function getChronicle(villageId: string, yearMonth: string) {
    const res = await api<{ data: VillageChronicleResponse }>(
      `/api/v1/villages/${villageId}/chronicles/${yearMonth}`,
    )
    return res.data
  }

  // ==========================================================================
  // ご縁スコア
  // ==========================================================================

  async function getSerendipityRanking(
    villageId: string,
    page?: number,
    size?: number,
  ) {
    const res = await api<{ data: VillageSerendipityRankingResponse }>(
      `/api/v1/villages/${villageId}/serendipity-scores/ranking${qs({ page, size })}`,
    )
    return res.data
  }

  async function getMyScore(villageId: string) {
    const res = await api<{ data: VillageSerendipityScoreResponse }>(
      `/api/v1/villages/${villageId}/serendipity-scores/me`,
    )
    return res.data
  }

  // ==========================================================================
  // 巡礼
  // ==========================================================================

  async function getTodaysPilgrimage() {
    const res = await api<{ data: VillagePilgrimageRecommendationResponse }>(
      '/api/v1/me/pilgrimage/today',
    )
    return res.data
  }

  async function recordVisit(recommendationId: string, body?: VillagePilgrimageVisitRecordRequest) {
    const res = await api<{ data: VillagePilgrimageVisitResponse }>(
      `/api/v1/me/pilgrimage/${recommendationId}/visit`,
      { method: 'POST', body },
    )
    return res.data
  }

  async function listMyVisits(page?: number, size?: number) {
    const res = await api<{ data: VillagePilgrimageVisitResponse[] }>(
      `/api/v1/me/pilgrimage/history${qs({ page, size })}`,
    )
    return res.data
  }

  // ==========================================================================
  // ニュースレター
  // ==========================================================================

  async function getNewsletterSettings(villageId: string) {
    const res = await api<{ data: VillageNewsletterSettingsResponse }>(
      `/api/v1/villages/${villageId}/newsletter`,
    )
    return res.data
  }

  async function updateNewsletterSettings(villageId: string, body: VillageNewsletterSettingsRequest) {
    const res = await api<{ data: VillageNewsletterSettingsResponse }>(
      `/api/v1/villages/${villageId}/newsletter`,
      { method: 'PUT', body },
    )
    return res.data
  }

  async function optOut(villageId: string) {
    const res = await api<{ data: VillageNewsletterOptOutResponse }>(
      `/api/v1/villages/${villageId}/newsletter/opt-out`,
      { method: 'POST' },
    )
    return res.data
  }

  async function optIn(villageId: string) {
    const res = await api<{ data: VillageNewsletterOptOutResponse }>(
      `/api/v1/villages/${villageId}/newsletter/opt-out`,
      { method: 'DELETE' },
    )
    return res.data
  }

  async function listSendLogs(villageId: string, frequency: string) {
    const res = await api<{ data: VillageNewsletterSendLogResponse[] }>(
      `/api/v1/villages/${villageId}/newsletter/send-logs?frequency=${frequency}`,
    )
    return res.data
  }

  return {
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
    listChronicles,
    getChronicle,
    getSerendipityRanking,
    getMyScore,
    getTodaysPilgrimage,
    recordVisit,
    listMyVisits,
    getNewsletterSettings,
    updateNewsletterSettings,
    optOut,
    optIn,
    listSendLogs,
  }
}
