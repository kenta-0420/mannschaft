import type {
  VillageMeetupCreateRequest,
  VillageMeetupListParams,
  VillageMeetupResponse,
  VillageMeetupUpdateRequest,
  VillageMeetupCandidateDateAddRequest,
  VillageMeetupVoteRequest,
  VillageMeetupVoteSummary,
  VillageChronicleListResponse,
  VillageChronicleResponse,
  VillageSerendipityRankingResponse,
  VillageSerendipityScoreResponse,
  VillagePilgrimageRecommendationResponse,
  VillagePilgrimageVisitRecordRequest,
  VillagePilgrimageVisitResponse,
  VillageNewsletterSettingsResponse,
  VillageNewsletterSettingsRequest,
  VillageNewsletterOptOutResponse,
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

  // ==========================================================================
  // 村史
  // ==========================================================================

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

  // ==========================================================================
  // ご縁スコア
  // ==========================================================================

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

  // ==========================================================================
  // 巡礼
  // ==========================================================================

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

  // ==========================================================================
  // ニュースレター
  // ==========================================================================

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
  }
}
