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

  return {
    getConnectionStatus,
    connect,
    disconnect,
    getPersonalSync,
    updatePersonalSync,
    manualSync,
  }
}
