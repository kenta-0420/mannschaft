import type { CoinTossResponse, CoinTossRequest } from '~/types/coin-toss'
import type { CursorMeta } from '~/types/api'

export function useCoinTossApi() {
  const api = useApi()

  function buildBase(teamId: number) {
    return `/api/v1/teams/${teamId}/coin-toss`
  }

  async function toss(teamId: number, body: CoinTossRequest) {
    return api<{ data: CoinTossResponse }>(buildBase(teamId), { method: 'POST', body })
  }

  async function getHistory(teamId: number, cursor?: string, limit: number = 20) {
    const query = new URLSearchParams()
    if (cursor) query.set('cursor', cursor)
    query.set('limit', String(limit))
    return api<{ data: CoinTossResponse[]; meta: CursorMeta }>(
      `${buildBase(teamId)}/history?${query}`,
    )
  }

  async function shareToChat(teamId: number, tossId: number) {
    return api(`${buildBase(teamId)}/${tossId}/share`, { method: 'POST' })
  }

  return {
    toss,
    getHistory,
    shareToChat,
  }
}
