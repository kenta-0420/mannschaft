export function useEmbedApi() {
  const api = useApi()

  function buildBase(orgId: number, tournamentId: number) {
    return `/api/v1/embed/organizations/${orgId}/tournaments/${tournamentId}`
  }

  async function getBracket(orgId: number, tournamentId: number) {
    return api<{ data: unknown }>(`${buildBase(orgId, tournamentId)}/bracket`)
  }

  async function getRankings(orgId: number, tournamentId: number, statKey: string) {
    return api<{ data: unknown }>(`${buildBase(orgId, tournamentId)}/rankings/${statKey}`)
  }

  async function getStandings(orgId: number, tournamentId: number, divisionId: number) {
    return api<{ data: unknown }>(`${buildBase(orgId, tournamentId)}/standings/${divisionId}`)
  }

  return {
    getBracket,
    getRankings,
    getStandings,
  }
}
