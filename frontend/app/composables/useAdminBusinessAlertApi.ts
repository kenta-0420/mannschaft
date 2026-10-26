import type { AdminBusinessAlertSummaryResponse } from '~/types/admin-business-alert'

export function useAdminBusinessAlertApi() {
  const api = useApi()

  function getSummary(): Promise<AdminBusinessAlertSummaryResponse> {
    return api<AdminBusinessAlertSummaryResponse>('/api/v1/admin/business-alerts/summary')
  }

  return { getSummary }
}
