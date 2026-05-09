// F10.6 Phase 10-γ-③-b: システムログAPIコンポーザブル
import type { SystemLogFileResponse, SystemLogType } from '~/types/system-log'

export function useSystemLogApi() {
  const { apiBase } = useRuntimeConfig().public

  const fetchLogFiles = async (
    type?: SystemLogType,
    date?: string,
  ): Promise<SystemLogFileResponse[]> => {
    const params: Record<string, string> = {}
    if (type) params.type = type
    if (date) params.date = date
    const res = await $fetch<{ data: SystemLogFileResponse[] }>(
      `${apiBase}/api/v1/system-admin/system-logs`,
      { params, credentials: 'include' },
    )
    return res.data
  }

  return { fetchLogFiles }
}
