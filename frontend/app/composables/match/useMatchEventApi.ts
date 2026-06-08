/**
 * F08.10 試合イベント記録 composable（04_frontend_and_ux.md §G.4）。
 *
 * 対象 API（events はチーム非依存・/organizations/{orgId}/matches/{matchId}/... 配下）:
 *   GET    /organizations/{orgId}/matches/{matchId}/events            listEvents（スコア整合チェック付き）
 *   POST   /organizations/{orgId}/matches/{matchId}/events            addEvent
 *   PATCH  /organizations/{orgId}/matches/{matchId}/events/{eventId}  updateEvent
 *   DELETE /organizations/{orgId}/matches/{matchId}/events/{eventId}  deleteEvent
 *   GET    /organizations/{orgId}/matches/{matchId}/appearances       listAppearances
 *
 * 3-A は素のイベント CRUD まで。交代/得点の連鎖束ね（linked_event_id）UX は 3-B。
 * matchId は UUID 文字列。エラーは useNotification 表示＋再 throw。
 */
import type {
  MatchEventsResponse,
  MatchEventResponse,
  MatchEventRequest,
  PlayerAppearanceResponse,
} from '~/types/match'

export function useMatchEventApi() {
  const api = useApi()
  const notification = useNotification()
  const { t } = useI18n()

  const base = (orgId: number, matchId: string) =>
    `/api/v1/organizations/${orgId}/matches/${matchId}`

  /** イベント一覧（events + 導出スコア + scoreMismatch） */
  async function listEvents(orgId: number, matchId: string): Promise<MatchEventsResponse> {
    try {
      const res = await api<{ data: MatchEventsResponse }>(`${base(orgId, matchId)}/events`)
      return res.data
    } catch (err) {
      notification.error(t('match.live.error.load_events_failed'))
      throw err
    }
  }

  /** 出場記録一覧（in/out 区間・computedMinutes） */
  async function listAppearances(
    orgId: number,
    matchId: string,
  ): Promise<PlayerAppearanceResponse[]> {
    try {
      const res = await api<{ data: PlayerAppearanceResponse[] }>(
        `${base(orgId, matchId)}/appearances`,
      )
      return res.data
    } catch (err) {
      notification.error(t('match.live.error.load_appearances_failed'))
      throw err
    }
  }

  /** イベント追加（素の CRUD・連鎖束ねは 3-B） */
  async function addEvent(
    orgId: number,
    matchId: string,
    body: MatchEventRequest,
  ): Promise<MatchEventResponse> {
    try {
      const res = await api<{ data: MatchEventResponse }>(`${base(orgId, matchId)}/events`, {
        method: 'POST',
        body,
      })
      return res.data
    } catch (err) {
      notification.error(t('match.live.error.add_event_failed'))
      throw err
    }
  }

  /** イベント更新 */
  async function updateEvent(
    orgId: number,
    matchId: string,
    eventId: string,
    body: MatchEventRequest,
  ): Promise<MatchEventResponse> {
    try {
      const res = await api<{ data: MatchEventResponse }>(
        `${base(orgId, matchId)}/events/${eventId}`,
        { method: 'PATCH', body },
      )
      return res.data
    } catch (err) {
      notification.error(t('match.live.error.update_event_failed'))
      throw err
    }
  }

  /** イベント削除 */
  async function deleteEvent(orgId: number, matchId: string, eventId: string): Promise<void> {
    try {
      await api(`${base(orgId, matchId)}/events/${eventId}`, { method: 'DELETE' })
    } catch (err) {
      notification.error(t('match.live.error.delete_event_failed'))
      throw err
    }
  }

  return {
    listEvents,
    listAppearances,
    addEvent,
    updateEvent,
    deleteEvent,
  }
}
