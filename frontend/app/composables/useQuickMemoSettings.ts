import type { UserQuickMemoSettingsResponse, UpdateSettingsRequest } from '~/types/quickMemo'

export function useQuickMemoSettings() {
  const api = useApi()

  async function getSettings() {
    return api<{ data: UserQuickMemoSettingsResponse }>('/api/v1/quick-memos/settings')
  }

  async function updateSettings(
    body: UpdateSettingsRequest,
    applyTo: 'NEW_ONLY' | 'UNSENT' | 'ALL' = 'NEW_ONLY',
  ) {
    return api<{ data: UserQuickMemoSettingsResponse }>(
      `/api/v1/quick-memos/settings?apply_to=${applyTo}`,
      { method: 'PUT', body },
    )
  }

  return { getSettings, updateSettings }
}
