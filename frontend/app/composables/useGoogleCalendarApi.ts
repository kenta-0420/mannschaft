export function useGoogleCalendarApi() {
  const api = useApi()

  async function getConnectionStatus() {
    return api<{
      data: { isConnected: boolean; email: string | null; lastSyncedAt: string | null }
    }>('/api/v1/me/google-calendar/status')
  }

  async function connect(body?: Record<string, unknown>) {
    return api<{ data: { authUrl: string } }>('/api/v1/me/google-calendar/connect', {
      method: 'POST',
      body,
    })
  }

  async function disconnect() {
    return api('/api/v1/me/google-calendar/disconnect', { method: 'DELETE' })
  }

  async function getPersonalSync() {
    return api('/api/v1/me/google-calendar/personal-sync')
  }

  async function updatePersonalSync(body: Record<string, unknown>) {
    return api('/api/v1/me/google-calendar/personal-sync', { method: 'PUT', body })
  }

  async function manualSync() {
    return api('/api/v1/me/google-calendar/sync', { method: 'POST' })
  }

  // === Team / Org Sync ===
  async function toggleTeamSync(teamId: number, body: Record<string, unknown>) {
    return api(`/api/v1/me/teams/${teamId}/calendar-sync`, { method: 'PUT', body })
  }

  async function toggleOrgSync(orgId: number, body: Record<string, unknown>) {
    return api(`/api/v1/me/organizations/${orgId}/calendar-sync`, { method: 'PUT', body })
  }

  async function getSyncSettings() {
    return api<{ data: unknown }>('/api/v1/me/calendar-sync-settings')
  }

  // === iCal ===
  async function getIcalToken() {
    return api<{ data: { token: string } }>('/api/v1/me/ical/token')
  }

  async function regenerateIcalToken() {
    return api<{ data: { token: string } }>('/api/v1/me/ical/token/regenerate', { method: 'POST' })
  }

  async function deleteIcalToken() {
    return api('/api/v1/me/ical/token', { method: 'DELETE' })
  }

  async function getIcalFeedUrl(token: string) {
    return `/ical/${token}.ics`
  }

  return {
    getConnectionStatus,
    connect,
    disconnect,
    getPersonalSync,
    updatePersonalSync,
    manualSync,
    toggleTeamSync,
    toggleOrgSync,
    getSyncSettings,
    getIcalToken,
    regenerateIcalToken,
    deleteIcalToken,
    getIcalFeedUrl,
  }
}
