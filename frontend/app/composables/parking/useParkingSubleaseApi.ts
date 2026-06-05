import type {
  SubleaseResponse,
  SubleaseDetailResponse,
  SubleasePaymentResponse,
  SubleaseApplicationResponse,
} from '~/types/parking'
import { buildBase } from './useParkingApiBase'

export function useParkingSubleaseApi() {
  const api = useApi()

  async function getSubleases(
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
    return api<{ data: SubleaseResponse[] }>(
      `${buildBase(scopeType, scopeId)}/parking/subleases${qs ? `?${qs}` : ''}`,
    )
  }

  async function createSublease(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api<{ data: SubleaseResponse }>(`${buildBase(scopeType, scopeId)}/parking/subleases`, {
      method: 'POST',
      body,
    })
  }

  async function getSublease(
    scopeType: 'team' | 'organization',
    scopeId: string,
    subleaseId: number,
  ) {
    return api<{ data: SubleaseDetailResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/subleases/${subleaseId}`,
    )
  }

  async function updateSublease(
    scopeType: 'team' | 'organization',
    scopeId: string,
    subleaseId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: SubleaseResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/subleases/${subleaseId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteSublease(
    scopeType: 'team' | 'organization',
    scopeId: string,
    subleaseId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/subleases/${subleaseId}`, {
      method: 'DELETE',
    })
  }

  async function applyToSublease(
    scopeType: 'team' | 'organization',
    scopeId: string,
    subleaseId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: SubleaseApplicationResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/subleases/${subleaseId}/apply`,
      { method: 'POST', body },
    )
  }

  async function approveSublease(
    scopeType: 'team' | 'organization',
    scopeId: string,
    subleaseId: number,
    body?: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/subleases/${subleaseId}/approve`, {
      method: 'PATCH',
      body,
    })
  }

  async function getSubleasePayments(
    scopeType: 'team' | 'organization',
    scopeId: string,
    subleaseId: number,
    params?: Record<string, unknown>,
  ) {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v != null) q.set(k, String(v))
      }
    const qs = q.toString()
    return api<{ data: SubleasePaymentResponse[] }>(
      `${buildBase(scopeType, scopeId)}/parking/subleases/${subleaseId}/payments${qs ? `?${qs}` : ''}`,
    )
  }

  async function terminateSublease(
    scopeType: 'team' | 'organization',
    scopeId: string,
    subleaseId: number,
    body?: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/subleases/${subleaseId}/terminate`, {
      method: 'PATCH',
      body,
    })
  }

  return {
    getSubleases,
    createSublease,
    getSublease,
    updateSublease,
    deleteSublease,
    applyToSublease,
    approveSublease,
    getSubleasePayments,
    terminateSublease,
  }
}
