export function useGoogleCalendarApi() {
  const api = useApi()

  async function getConnectionStatus() {
    return api<{ data: { isConnected: boolean; email: string | null; lastSyncedAt: string | null } }>('/api/v1/google-calendar/status')
  }

  async function connect() {
    return api<{ data: { authUrl: string } }>('/api/v1/google-calendar/connect', { method: 'POST' })
  }

  async function disconnect() {
    return api('/api/v1/google-calendar/disconnect', { method: 'POST' })
  }

  async function getSyncSettings() {
    return api<{ data: { personalSync: boolean; teamSyncIds: number[]; orgSyncIds: number[] } }>('/api/v1/google-calendar/sync-settings')
  }

  async function updateSyncSettings(body: { personalSync?: boolean; teamSyncIds?: number[]; orgSyncIds?: number[] }) {
    return api('/api/v1/google-calendar/sync-settings', { method: 'PUT', body })
  }

  async function manualSync() {
    return api('/api/v1/google-calendar/sync', { method: 'POST' })
  }

  async function getICalUrl(scopeType: 'team' | 'organization' | 'personal', scopeId?: number) {
    const query = new URLSearchParams()
    query.set('scopeType', scopeType)
    if (scopeId) query.set('scopeId', String(scopeId))
    return api<{ data: { icalUrl: string } }>(`/api/v1/google-calendar/ical?${query}`)
  }

  return {
    getConnectionStatus, connect, disconnect,
    getSyncSettings, updateSyncSettings,
    manualSync, getICalUrl,
  }
}
