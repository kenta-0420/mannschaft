export function useShiftUtilApi() {
  const BASE = '/api/v1/shifts/schedules'

  // === オープンコール ===
  async function claimOpenCall(swapRequestId: number): Promise<void> {
    const api = useApi()
    await api(`${BASE}/swap-requests/${swapRequestId}/claim`, { method: 'POST' })
  }

  async function selectClaimer(swapRequestId: number, claimedBy: number): Promise<void> {
    const api = useApi()
    await api(`${BASE}/swap-requests/${swapRequestId}/select-claimer`, {
      method: 'POST',
      body: { claimedBy },
    })
  }

  // === PDF ===
  async function downloadShiftPdf(scheduleId: number, layout: 'team' | 'personal'): Promise<Blob> {
    const config = useRuntimeConfig()
    const { accessToken } = useAuthStore()
    return $fetch<Blob>(`${config.public.apiBase}/api/v1${BASE}/${scheduleId}/pdf?layout=${layout}`, {
      responseType: 'blob',
      headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
    })
  }

  return {
    claimOpenCall,
    selectClaimer,
    downloadShiftPdf,
  }
}
