/**
 * F08.10 試合集計取得 composable（04_frontend_and_ux.md §G.4）。
 *
 * 対象 API（/organizations/{orgId}/... 配下・orgId/userId/teamId は数値）:
 *   GET /organizations/{orgId}/users/{userId}/match-stats                     getUserStats（横断キャリア統計）
 *   GET /organizations/{orgId}/users/{userId}/match-stats/timeline            getUserTimeline（ページング）
 *   GET /organizations/{orgId}/users/{userId}/teams/{teamId}/match-stats      getUserTeamStats（チーム別）
 *   GET /organizations/{orgId}/teams/{teamId}/match-stats                     getTeamStats
 *
 * いずれも from/to/kind/sport でフィルタ可。timeline は page/size 追加。
 * エラーは useNotification 表示＋再 throw。
 */
import type {
  UserMatchStatsResponse,
  TeamMatchStatsResponse,
  MatchStatsParams,
  UserTimelineParams,
} from '~/types/match'
import type { components } from '~/types/generated'

type PagedUserTimeline = components['schemas']['PagedResponseUserMatchTimelineEntry']

export function useMatchAnalytics() {
  const api = useApi()
  const notification = useNotification()
  const { t } = useI18n()

  const orgBase = (orgId: number) => `/api/v1/organizations/${orgId}`

  /** 個人キャリア統計（org 横断・チーム非依存） */
  async function getUserStats(
    orgId: number,
    userId: number,
    params?: MatchStatsParams,
  ): Promise<UserMatchStatsResponse> {
    try {
      const res = await api<{ data: UserMatchStatsResponse }>(
        `${orgBase(orgId)}/users/${userId}/match-stats`,
        { params },
      )
      return res.data
    } catch (err) {
      notification.error(t('match.analytics.error.load_failed'))
      throw err
    }
  }

  /** 個人試合タイムライン（ページング） */
  async function getUserTimeline(
    orgId: number,
    userId: number,
    params?: UserTimelineParams,
  ): Promise<PagedUserTimeline> {
    try {
      return await api<PagedUserTimeline>(
        `${orgBase(orgId)}/users/${userId}/match-stats/timeline`,
        { params },
      )
    } catch (err) {
      notification.error(t('match.analytics.error.load_failed'))
      throw err
    }
  }

  /** 個人のチーム別統計（teamId 指定） */
  async function getUserTeamStats(
    orgId: number,
    userId: number,
    teamId: number,
    params?: MatchStatsParams,
  ): Promise<UserMatchStatsResponse> {
    try {
      const res = await api<{ data: UserMatchStatsResponse }>(
        `${orgBase(orgId)}/users/${userId}/teams/${teamId}/match-stats`,
        { params },
      )
      return res.data
    } catch (err) {
      notification.error(t('match.analytics.error.load_failed'))
      throw err
    }
  }

  /** チーム統計（勝敗・得失点・選手ランキング） */
  async function getTeamStats(
    orgId: number,
    teamId: number,
    params?: MatchStatsParams,
  ): Promise<TeamMatchStatsResponse> {
    try {
      const res = await api<{ data: TeamMatchStatsResponse }>(
        `${orgBase(orgId)}/teams/${teamId}/match-stats`,
        { params },
      )
      return res.data
    } catch (err) {
      notification.error(t('match.analytics.error.load_failed'))
      throw err
    }
  }

  return {
    getUserStats,
    getUserTimeline,
    getUserTeamStats,
    getTeamStats,
  }
}
