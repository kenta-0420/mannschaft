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

  function buildBase(teamId: number) {
    return `/api/v1/teams/${teamId}/presence`
  }

  async function getStatus(teamId: number) {
    return api<{ data: PresenceStatusResponse[] }>(`${buildBase(teamId)}/status`)
  }

  async function goingOut(teamId: number, body: PresenceGoingOutRequest) {
    return api<{ data: PresenceEventResponse }>(`${buildBase(teamId)}/going-out`, {
      method: 'POST',
      body,
    })
  }

  async function goHome(teamId: number, body?: PresenceHomeRequest) {
    return api<{ data: PresenceEventResponse }>(`${buildBase(teamId)}/home`, {
      method: 'POST',
      body: body ?? {},
    })
  }

  async function getHistory(teamId: number, cursor?: string, limit: number = 20) {
    const query = new URLSearchParams()
    if (cursor) query.set('cursor', cursor)
    query.set('limit', String(limit))
    return api<{ data: PresenceEventResponse[]; meta: CursorMeta }>(
      `${buildBase(teamId)}/history?${query}`,
    )
  }

  async function getStats(teamId: number) {
    return api<{ data: PresenceStatsResponse }>(`${buildBase(teamId)}/stats`)
  }

  async function getIcons(teamId: number) {
    return api<{ data: PresenceIconResponse[] }>(`${buildBase(teamId)}/icons`)
  }

  async function updateIcons(teamId: number, body: PresenceIconRequest) {
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
