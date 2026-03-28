import type { DutyRotationResponse, DutyRotationRequest, DutyTodayResponse } from '~/types/duty'

export function useDutyApi() {
  const api = useApi()

  function buildBase(teamId: number) {
    return `/api/v1/teams/${teamId}/duties`
  }

  async function listDuties(teamId: number) {
    return api<{ data: DutyRotationResponse[] }>(buildBase(teamId))
  }

  async function createDuty(teamId: number, body: DutyRotationRequest) {
    return api<{ data: DutyRotationResponse }>(buildBase(teamId), { method: 'POST', body })
  }

  async function updateDuty(teamId: number, dutyId: number, body: DutyRotationRequest) {
    return api<{ data: DutyRotationResponse }>(`${buildBase(teamId)}/${dutyId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteDuty(teamId: number, dutyId: number) {
    return api(`${buildBase(teamId)}/${dutyId}`, { method: 'DELETE' })
  }

  async function getTodayDuties(teamId: number) {
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
