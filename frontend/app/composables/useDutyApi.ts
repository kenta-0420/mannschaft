import type { DutyRotationResponse, DutyRotationRequest, DutyTodayResponse } from '~/types/duty'

export function useDutyApi() {
  const api = useApi()

  function buildBase(teamId: string) {
    return `/api/v1/teams/${teamId}/duties`
  }

  async function listDuties(teamId: string) {
    return api<{ data: DutyRotationResponse[] }>(buildBase(teamId))
  }

  async function createDuty(teamId: string, body: DutyRotationRequest) {
    return api<{ data: DutyRotationResponse }>(buildBase(teamId), { method: 'POST', body })
  }

  async function updateDuty(teamId: string, dutyId: number, body: DutyRotationRequest) {
    return api<{ data: DutyRotationResponse }>(`${buildBase(teamId)}/${dutyId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteDuty(teamId: string, dutyId: number) {
    return api(`${buildBase(teamId)}/${dutyId}`, { method: 'DELETE' })
  }

  async function getTodayDuties(teamId: string) {
    return api<{ data: DutyTodayResponse[] }>(`${buildBase(teamId)}/today`)
  }

  return {
    listDuties,
    createDuty,
    updateDuty,
    deleteDuty,
    getTodayDuties,
  }
}
