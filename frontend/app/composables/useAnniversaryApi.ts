import type { AnniversaryResponse, AnniversaryRequest } from '~/types/anniversary'

export function useAnniversaryApi() {
  const api = useApi()

  function buildBase(teamId: string) {
    return `/api/v1/teams/${teamId}/anniversaries`
  }

  async function listAnniversaries(teamId: string) {
    return api<{ data: AnniversaryResponse[] }>(buildBase(teamId))
  }

  async function createAnniversary(teamId: string, body: AnniversaryRequest) {
    return api<{ data: AnniversaryResponse }>(buildBase(teamId), { method: 'POST', body })
  }

  async function updateAnniversary(
    teamId: string,
    anniversaryId: number,
    body: AnniversaryRequest,
  ) {
    return api<{ data: AnniversaryResponse }>(`${buildBase(teamId)}/${anniversaryId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteAnniversary(teamId: string, anniversaryId: number) {
    return api(`${buildBase(teamId)}/${anniversaryId}`, { method: 'DELETE' })
  }

  async function getUpcoming(teamId: string) {
    return api<{ data: AnniversaryResponse[] }>(`${buildBase(teamId)}/upcoming`)
  }

  return {
    listAnniversaries,
    createAnniversary,
    updateAnniversary,
    deleteAnniversary,
    getUpcoming,
  }
}
