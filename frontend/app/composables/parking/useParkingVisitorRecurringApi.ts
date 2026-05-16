import type { VisitorRecurringResponse } from '~/types/parking'
import { buildBase } from './useParkingApiBase'

export function useParkingVisitorRecurringApi() {
  const api = useApi()

  async function getVisitorRecurring(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: VisitorRecurringResponse[] }>(
      `${buildBase(scopeType, scopeId)}/parking/visitor-recurring`,
    )
  }

  async function createVisitorRecurring(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: VisitorRecurringResponse }>(
      `${buildBase(scopeType, scopeId)}/parking/visitor-recurring`,
      { method: 'POST', body },
    )
  }

  async function updateVisitorRecurring(
    scopeType: 'team' | 'organization',
    scopeId: number,
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
    scopeId: number,
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
