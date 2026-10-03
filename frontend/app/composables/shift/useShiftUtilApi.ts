export function useShiftUtilApi() {
  const BASE = '/api/v1/shifts/schedules'

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
    downloadShiftPdf,
  }
}
