// F10.6 Phase 10-γ-③-b: システムログAPIコンポーザブル
import type { SystemLogFileResponse, SystemLogType } from '~/types/system-log'

export function useSystemLogApi() {
  const api = useApi()

  const fetchLogFiles = async (
    type?: SystemLogType,
    date?: string,
  ): Promise<SystemLogFileResponse[]> => {
    const params: Record<string, string> = {}
    if (type) params.type = type
    if (date) params.date = date
    const res = await api<{ data: SystemLogFileResponse[] }>(
      `/api/v1/system-admin/system-logs`,
      { params },
    )
    return res.data
  }

  return { fetchLogFiles }
}
