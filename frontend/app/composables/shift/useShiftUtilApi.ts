export function useShiftUtilApi() {
  const api = useApi()
  const BASE = '/api/v1/shifts/schedules'

  // === PDF ===
  // useApi は credentials: 'include'（HttpOnly Cookie）と 401 時の自動リフレッシュを備えるため、
  // 他の blob ダウンロード実装（useScheduleAnalytics 等）と同じく useApi 経由で取得する。
  async function downloadShiftPdf(scheduleId: number, layout: 'team' | 'personal'): Promise<Blob> {
    // 型引数 <Blob> を明示すると ResponseType が 'json' に固定され型エラーになるため、
    // useAdvertiserApi の blob ダウンロードと同じく型引数なし + `as const` で Blob を推論させる。
    return api(`${BASE}/${scheduleId}/pdf?layout=${layout}`, {
      responseType: 'blob' as const,
    })
  }

  return {
    downloadShiftPdf,
  }
}
