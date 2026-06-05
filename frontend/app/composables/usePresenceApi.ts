import type {
  PresenceStatusResponse,
  PresenceGoingOutRequest,
  PresenceHomeRequest,
  PresenceEventResponse,
  PresenceStatsResponse,
  PresenceIconResponse,
  PresenceIconRequest,
} from '~/types/presence'
import type { CursorMeta } from '~/types/api'

export function usePresenceApi() {
  const api = useApi()

  function buildBase(teamId: string) {
    return `/api/v1/teams/${teamId}/presence`
  }

  async function getStatus(teamId: string) {
    return api<{ data: PresenceStatusResponse[] }>(`${buildBase(teamId)}/status`)
  }

  async function goingOut(teamId: string, body: PresenceGoingOutRequest) {
    return api<{ data: PresenceEventResponse }>(`${buildBase(teamId)}/going-out`, {
      method: 'POST',
      body,
    })
  }

  async function goHome(teamId: string, body?: PresenceHomeRequest) {
    return api<{ data: PresenceEventResponse }>(`${buildBase(teamId)}/home`, {
      method: 'POST',
      body: body ?? {},
    })
  }

  async function getHistory(teamId: string, cursor?: string, limit: number = 20) {
    const query = new URLSearchParams()
    if (cursor) query.set('cursor', cursor)
    query.set('limit', String(limit))
    return api<{ data: PresenceEventResponse[]; meta: CursorMeta }>(
      `${buildBase(teamId)}/history?${query}`,
    )
  }

  async function getStats(teamId: string) {
    return api<{ data: PresenceStatsResponse }>(`${buildBase(teamId)}/stats`)
  }

  async function getIcons(teamId: string) {
    return api<{ data: PresenceIconResponse[] }>(`${buildBase(teamId)}/icons`)
  }

  async function updateIcons(teamId: string, body: PresenceIconRequest) {
    return api(`${buildBase(teamId)}/icons`, { method: 'PUT', body })
  }

  return {
    getStatus,
    goingOut,
    goHome,
    getHistory,
    getStats,
    getIcons,
    updateIcons,
  }
}
