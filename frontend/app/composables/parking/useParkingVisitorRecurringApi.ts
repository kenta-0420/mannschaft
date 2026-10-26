import type { VisitorRecurringResponse } from '~/types/parking'
import { buildBase } from './useParkingApiBase'

export function useParkingVisitorRecurringApi() {
  const api = useApi()

  async function getVisitorRecurring(scopeType: 'team' | 'organization', scopeId: string) {
    return api<{ data: VisitorRecurringResponse[] }>(
      `${buildBase(scopeType, scopeId)}/parking/visitor-recurring`,
    )
  }

  async function createVisitorRecurring(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: Record<string, unknown>,
  ) {
    return api<{ data: VisitorRecurringResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/visitor-recurring`,
      { method: 'POST', body },
    )
  }

  async function updateVisitorRecurring(
    scopeType: 'team' | 'organization',
    scopeId: string,
    recurringId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: VisitorRecurringResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/visitor-recurring/${recurringId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteVisitorRecurring(
    scopeType: 'team' | 'organization',
    scopeId: string,
    recurringId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/parking/visitor-recurring/${recurringId}`, {
      method: 'DELETE',
    })
  }

  return {
    getVisitorRecurring,
    createVisitorRecurring,
    updateVisitorRecurring,
    deleteVisitorRecurring,
  }
}
