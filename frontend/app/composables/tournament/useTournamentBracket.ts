// ブラケット生成・試合進行（試合日・対戦表・試合・ロースター・順位表・昇降格・ランキング）を担当
import type {
  TournamentMatchday,
  TournamentMatrix,
  TournamentMatch,
  TournamentRoster,
  TournamentStanding,
  PromotionRecord,
  IndividualRanking,
  RankingSummary,
} from '~/types/tournament'

/** ページネーション付きレスポンス（BE PagedResponse 整合）。 */
interface PagedResult<T> {
  data: T[]
  meta: { total: number; page: number; size: number; totalPages: number }
}

export function useTournamentBracket() {
  const api = useApi()
  const b = (orgId: string) => `/api/v1/organizations/${orgId}`

  // PDF（Blob）取得は ofetch インスタンスの型が responseType:'json' に固定されるため、
  // 既存の Blob ダウンロード作法（usePropertyWorkPackageApi）と同様に生の $fetch を使う。
  async function fetchBlob(path: string): Promise<Blob> {
    const config = useRuntimeConfig()
    const { accessToken } = useAuthStore()
    return $fetch<Blob>(`${config.public.apiBase as string}${path}`, {
      responseType: 'blob',
      credentials: 'include',
      headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
    })
  }

  // === Matchdays ===
  async function getMatchdays(orgId: string, tId: number, divId: number) {
    return api<{ data: TournamentMatchday[] }>(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/matchdays`,
    )
  }
  async function createMatchday(
    orgId: string,
    tId: number,
    divId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/matchdays`, {
      method: 'POST',
      body,
    })
  }
  async function generateMatchdays(orgId: string, tId: number, divId: number) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/matchdays/generate`, {
      method: 'POST',
    })
  }
  async function batchUpdateScores(
    orgId: string,
    tId: number,
    divId: number,
    mdId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/matchdays/${mdId}/scores/batch`, {
      method: 'POST',
      body,
    })
  }
  // CSV スコア取込。BE は multipart/form-data の @RequestParam("file") を受ける。
  // ofetch は FormData を渡すと Content-Type を自動付与（boundary 込み）するため、
  // ここでは FormData をそのまま body に渡す（手動で Content-Type を設定すると boundary が欠落して壊れる）。
  async function importScores(
    orgId: string,
    tId: number,
    divId: number,
    mdId: number,
    formData: FormData,
  ) {
    return api(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/matchdays/${mdId}/scores/import`,
      { method: 'POST', body: formData },
    )
  }

  // === Matrix ===
  async function getMatrix(orgId: string, tId: number, divId: number) {
    return api<{ data: TournamentMatrix }>(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/matrix`,
    )
  }
  async function getMatrixPdf(orgId: string, tId: number, divId: number) {
    return fetchBlob(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/matrix/pdf`)
  }

  // === Matches ===
  async function getMatch(orgId: string, tId: number, matchId: number) {
    return api<{ data: TournamentMatch }>(`${b(orgId)}/tournaments/${tId}/matches/${matchId}`)
  }
  async function updateMatchScore(
    orgId: string,
    tId: number,
    matchId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/matches/${matchId}/score`, { method: 'PATCH', body })
  }
  async function updateMatchStatus(
    orgId: string,
    tId: number,
    matchId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/matches/${matchId}/status`, {
      method: 'PATCH',
      body,
    })
  }
  async function updatePlayerStats(
    orgId: string,
    tId: number,
    matchId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/matches/${matchId}/player-stats`, {
      method: 'PATCH',
      body,
    })
  }

  // === Rosters ===
  async function getRosters(orgId: string, tId: number, matchId: number) {
    return api<{ data: TournamentRoster[] }>(
      `${b(orgId)}/tournaments/${tId}/matches/${matchId}/rosters`,
    )
  }
  async function addRoster(
    orgId: string,
    tId: number,
    matchId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/matches/${matchId}/rosters`, {
      method: 'POST',
      body,
    })
  }
  async function removeRoster(orgId: string, tId: number, matchId: number, rosterId: number) {
    return api(`${b(orgId)}/tournaments/${tId}/matches/${matchId}/rosters/${rosterId}`, {
      method: 'DELETE',
    })
  }

  // === Standings ===
  async function getStandings(orgId: string, tId: number, divId: number) {
    return api<{ data: TournamentStanding[] }>(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/standings`,
    )
  }
  async function getStandingsPdf(orgId: string, tId: number, divId: number) {
    return fetchBlob(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/standings/pdf`)
  }
  async function recalculateStandings(orgId: string, tId: number, divId: number) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/standings/recalculate`, {
      method: 'POST',
    })
  }

  // === Promotions ===
  async function getPromotions(orgId: string, tId: number) {
    return api<{ data: PromotionRecord[] }>(`${b(orgId)}/tournaments/${tId}/promotions`)
  }
  async function createPromotion(orgId: string, tId: number, body: Record<string, unknown>) {
    return api(`${b(orgId)}/tournaments/${tId}/promotions`, { method: 'POST', body })
  }
  async function previewPromotions(orgId: string, tId: number) {
    return api<{ data: PromotionRecord[] }>(
      `${b(orgId)}/tournaments/${tId}/promotions/preview`,
      { method: 'POST' },
    )
  }

  // === Rankings ===
  // 全ランキング一覧（カテゴリ別サマリ）。BE は RankingSummaryResponse を {data} で返す。
  async function getRankings(orgId: string, tId: number) {
    return api<{ data: RankingSummary }>(`${b(orgId)}/tournaments/${tId}/rankings`)
  }
  // statKey 別の個人ランキング。BE は PagedResponse（{data, meta}）で返す。
  async function getIndividualRankings(
    orgId: string,
    tId: number,
    statKey: string,
    params?: { page?: number; size?: number },
  ) {
    const q = new URLSearchParams()
    if (params?.page != null) q.set('page', String(params.page))
    if (params?.size != null) q.set('size', String(params.size))
    const suffix = q.toString() ? `?${q}` : ''
    return api<PagedResult<IndividualRanking>>(
      `${b(orgId)}/tournaments/${tId}/rankings/${statKey}${suffix}`,
    )
  }
  async function getRankingsPdf(orgId: string, tId: number, statKey: string) {
    return fetchBlob(`${b(orgId)}/tournaments/${tId}/rankings/${statKey}/pdf`)
  }

  // === Bracket PDF ===
  async function getBracketPdf(orgId: string, tId: number) {
    return fetchBlob(`${b(orgId)}/tournaments/${tId}/bracket/pdf`)
  }

  return {
    getMatchdays,
    createMatchday,
    generateMatchdays,
    batchUpdateScores,
    importScores,
    getMatrix,
    getMatrixPdf,
    getMatch,
    updateMatchScore,
    updateMatchStatus,
    updatePlayerStats,
    getRosters,
    addRoster,
    removeRoster,
    getStandings,
    getStandingsPdf,
    recalculateStandings,
    getPromotions,
    createPromotion,
    previewPromotions,
    getRankings,
    getIndividualRankings,
    getRankingsPdf,
    getBracketPdf,
  }
}
