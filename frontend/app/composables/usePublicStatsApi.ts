import type { PublicStats } from '~/types/public-stats'

export function usePublicStatsApi() {
  const api = useApi()

  async function getPublicStats(): Promise<PublicStats> {
    const res = await api<{ data: PublicStats }>('/api/v1/public/stats')
    return res.data
  }

  return { getPublicStats }
}
