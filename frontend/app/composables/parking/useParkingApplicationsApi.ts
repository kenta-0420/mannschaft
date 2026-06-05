import type { ApplicationResponse } from '~/types/parking'
import { buildBase } from './useParkingApiBase'

export function useParkingApplicationsApi() {
  const api = useApi()

  async function getApplications(
    scopeType: 'team' | 'organization',
    scopeId: string,
    params?: Record<string, unknown>,
  ) {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v != null) q.set(k, String(v))
      }
    const qs = q.toString()
    return api<{ data: ApplicationResponse[] }>(
      `${buildBase(scopeType, scopeId)}/parking/applications${qs ? `?${qs}` : ''}`,
    )
  }

  async function createApplication(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api<{ data: ApplicationResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/applications`,
      { method: 'POST', body },
    )
  }

  async function deleteApplication(
    scopeType: 'team' | 'organization',
    scopeId: string,
    applicationId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/applications/${applicationId}`, {
      method: 'DELETE',
    })
  }

  async function approveApplication(
    scopeType: 'team' | 'organization',
    scopeId: string,
    applicationId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/applications/${applicationId}/approve`, {
      method: 'PATCH',
    })
  }

  async function rejectApplication(
    scopeType: 'team' | 'organization',
    scopeId: string,
    applicationId: number,
    body?: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/applications/${applicationId}/reject`, {
      method: 'PATCH',
      body,
    })
  }

  async function runApplicationLottery(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body?: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/applications/lottery`, {
      method: 'POST',
      body,
    })
  }

  return {
    getApplications,
    createApplication,
    deleteApplication,
    approveApplication,
    rejectApplication,
    runApplicationLottery,
  }
}
