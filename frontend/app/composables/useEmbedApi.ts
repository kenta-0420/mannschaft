export function useEmbedApi() {
  const api = useApi()

  function buildBase(orgId: string, tournamentId: number) {
    return `/api/v1/embed/organizations/${orgId}/tournaments/${tournamentId}`
  }

  async function getBracket(orgId: string, tournamentId: number) {
    return api<{ data: unknown }>(`${buildBase(orgId, tournamentId)}/bracket`)
  }

  async function getRankings(orgId: string, tournamentId: number, statKey: string) {
    return api<{ data: unknown }>(`${buildBase(orgId, tournamentId)}/rankings/${statKey}`)
  }

  async function getStandings(orgId: string, tournamentId: number, divisionId: number) {
    return api<{ data: unknown }>(`${buildBase(orgId, tournamentId)}/standings/${divisionId}`)
  }

  return {
    getBracket,
    getRankings,
    getStandings,
  }
}
