/**
 * F08.10 単独試合 CRUD composable（04_frontend_and_ux.md §G.4）。
 *
 * 対象 API（/api/v1/organizations/{orgId}/... 配下・orgId/teamId は数値）:
 *   GET    /organizations/{orgId}/teams/{teamId}/matches              listMatches（Phase2C 一覧・ページング）
 *   POST   /organizations/{orgId}/teams/{teamId}/matches              createMatch
 *   GET    /organizations/{orgId}/teams/{teamId}/matches/{matchId}    getMatch
 *   PATCH  /organizations/{orgId}/teams/{teamId}/matches/{matchId}    updateMatch
 *   PATCH  .../matches/{matchId}/status                               changeStatus
 *   PATCH  .../matches/{matchId}/score                                finalizeScore
 *   PATCH  .../matches/{matchId}/recording-mode                       changeRecordingMode
 *   DELETE /organizations/{orgId}/teams/{teamId}/matches/{matchId}    deleteMatch
 *
 * orgId / teamId は引数化する（現在 org コンテキストの解決はページ側で行う＝
 * teamApi.getOrganizations 由来。useRepairPlanKanbanApi/usePagesteams の既存作法）。
 * エラーは握りつぶさず useNotification で表示し再 throw（useMatchRoster パターン踏襲）。
 */
import type {
  PagedResponseMatchSummaryResponse,
  MatchSummaryResponse,
  MatchResponse,
  CreateMatchRequest,
  UpdateMatchRequest,
  ChangeStatusRequest,
  FinalizeScoreRequest,
  ChangeRecordingModeRequest,
  ListMatchesParams,
} from '~/types/match'

export function useMatchApi() {
  const api = useApi()
  const notification = useNotification()
  const { t } = useI18n()

  const base = (orgId: number, teamId: number) =>
    `/api/v1/organizations/${orgId}/teams/${teamId}/matches`

  /** 試合一覧（kind/status/sport/from/to/page/size でフィルタ・ページング） */
  async function listMatches(
    orgId: number,
    teamId: number,
    params?: ListMatchesParams,
  ): Promise<PagedResponseMatchSummaryResponse> {
    try {
      return await api<PagedResponseMatchSummaryResponse>(base(orgId, teamId), {
        params,
      })
    } catch (err) {
      notification.error(t('match.list.error.load_failed'))
      throw err
    }
  }

  /** 試合詳細 */
  async function getMatch(
    orgId: number,
    teamId: number,
    matchId: string,
  ): Promise<MatchResponse> {
    try {
      const res = await api<{ data: MatchResponse }>(`${base(orgId, teamId)}/${matchId}`)
      return res.data
    } catch (err) {
      notification.error(t('match.list.error.load_failed'))
      throw err
    }
  }

  /**
   * カレンダー予定（入口④）に紐づく既存試合を解決する。
   * 既存があれば live を開き・無ければ作成する二重起票防止の判定に用いる。
   * BE は存在しない場合 200 + data:null を返すため、戻り値も `MatchSummaryResponse | null`。
   */
  async function resolveMatchBySchedule(
    orgId: number,
    teamId: number,
    scheduleId: number,
  ): Promise<MatchSummaryResponse | null> {
    try {
      const res = await api<{ data: MatchSummaryResponse | null }>(
        `${base(orgId, teamId)}/by-schedule/${scheduleId}`,
      )
      return res.data ?? null
    } catch (err) {
      notification.error(t('match.list.error.load_failed'))
      throw err
    }
  }

  /** 試合作成（クイックスタート＝kind＋相手名が最小） */
  async function createMatch(
    orgId: number,
    teamId: number,
    body: CreateMatchRequest,
  ): Promise<MatchResponse> {
    try {
      const res = await api<{ data: MatchResponse }>(base(orgId, teamId), {
        method: 'POST',
        body,
      })
      notification.success(t('match.create.success'))
      return res.data
    } catch (err) {
      notification.error(t('match.create.error.create_failed'))
      throw err
    }
  }

  /** 試合更新（venue/duration/日時/メモ等の後追い補完） */
  async function updateMatch(
    orgId: number,
    teamId: number,
    matchId: string,
    body: UpdateMatchRequest,
  ): Promise<MatchResponse> {
    try {
      const res = await api<{ data: MatchResponse }>(`${base(orgId, teamId)}/${matchId}`, {
        method: 'PATCH',
        body,
      })
      notification.success(t('match.create.update_success'))
      return res.data
    } catch (err) {
      notification.error(t('match.create.error.update_failed'))
      throw err
    }
  }

  /** ステータス遷移（PLANNED/IN_PROGRESS/COMPLETED/CLOSED/CANCELLED） */
  async function changeStatus(
    orgId: number,
    teamId: number,
    matchId: string,
    body: ChangeStatusRequest,
  ): Promise<MatchResponse> {
    try {
      const res = await api<{ data: MatchResponse }>(`${base(orgId, teamId)}/${matchId}/status`, {
        method: 'PATCH',
        body,
      })
      return res.data
    } catch (err) {
      notification.error(t('match.list.error.status_failed'))
      throw err
    }
  }

  /** スコア確定 */
  async function finalizeScore(
    orgId: number,
    teamId: number,
    matchId: string,
    body: FinalizeScoreRequest,
  ): Promise<MatchResponse> {
    try {
      const res = await api<{ data: MatchResponse }>(`${base(orgId, teamId)}/${matchId}/score`, {
        method: 'PATCH',
        body,
      })
      return res.data
    } catch (err) {
      notification.error(t('match.list.error.score_failed'))
      throw err
    }
  }

  /** 記録モード変更（記録係の有無・割当） */
  async function changeRecordingMode(
    orgId: number,
    teamId: number,
    matchId: string,
    body: ChangeRecordingModeRequest,
  ): Promise<MatchResponse> {
    try {
      const res = await api<{ data: MatchResponse }>(
        `${base(orgId, teamId)}/${matchId}/recording-mode`,
        { method: 'PATCH', body },
      )
      return res.data
    } catch (err) {
      notification.error(t('match.list.error.recording_mode_failed'))
      throw err
    }
  }

  /** 試合削除 */
  async function deleteMatch(orgId: number, teamId: number, matchId: string): Promise<void> {
    try {
      await api(`${base(orgId, teamId)}/${matchId}`, { method: 'DELETE' })
      notification.success(t('match.list.delete_success'))
    } catch (err) {
      notification.error(t('match.list.error.delete_failed'))
      throw err
    }
  }

  return {
    listMatches,
    getMatch,
    resolveMatchBySchedule,
    createMatch,
    updateMatch,
    changeStatus,
    finalizeScore,
    changeRecordingMode,
    deleteMatch,
  }
}
